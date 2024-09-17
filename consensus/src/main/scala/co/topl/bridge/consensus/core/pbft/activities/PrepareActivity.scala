package co.topl.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.pbft.Prepared
import co.topl.bridge.consensus.core.pbft.RequestStateManager
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.implicits._
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import org.typelevel.log4cats.Logger

import java.security.PublicKey
import co.topl.bridge.shared.ReplicaCount

object PrepareActivity {

  private sealed trait PrepareProblem extends Throwable
  private case object InvalidPrepareSignature extends PrepareProblem
  private case object InvalidView extends PrepareProblem
  private case object InvalidWatermark extends PrepareProblem
  import cats.implicits._

  def apply[F[_]: Async: Logger](
      request: PrepareRequest
  )(
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      requestStateManager: RequestStateManager[F],
      currentViewRef: CurrentViewRef[F],
      storageApi: StorageApi[F],
      replicaCount: ReplicaCount,
      replica: ReplicaId
  ): F[Unit] = {
    import org.typelevel.log4cats.syntax._
    (for {
      reqSignCheck <- checkMessageSignature(
        request.replicaId,
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <- Async[F].raiseUnless(reqSignCheck)(
        InvalidPrepareSignature
      )
      viewNumberCheck <- checkViewNumber(request.viewNumber)
      _ <- Async[F].raiseUnless(viewNumberCheck)(
        InvalidView
      )
      waterMarkCheck <- checkWaterMark()
      _ <- Async[F].raiseUnless(waterMarkCheck)(
        InvalidWatermark
      )
      _ <- storageApi.insertPrepareMessage(request)
      isPrepared <- isPrepared[F](
        request.viewNumber,
        request.sequenceNumber
      )
      _ <- requestStateManager.processEvent(Prepared(request)).whenA(isPrepared)
    } yield ()).handleErrorWith {
      _ match {
        case InvalidPrepareSignature =>
          error"Invalid Prepare signature"
        case InvalidView =>
          error"Invalid view number"
        case InvalidWatermark =>
          error"Invalid watermark"
      }
    }
  }

}

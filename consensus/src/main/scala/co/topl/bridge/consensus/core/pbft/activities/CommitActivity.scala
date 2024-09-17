package co.topl.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import cats.implicits._
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.pbft.Commited
import co.topl.bridge.consensus.core.pbft.RequestStateManager
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.implicits._
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import org.typelevel.log4cats.Logger

import java.security.PublicKey

object CommitActivity {

  private sealed trait CommitProblem extends Throwable
  private case object InvalidPrepareSignature extends CommitProblem
  private case object InvalidView extends CommitProblem
  private case object InvalidWatermark extends CommitProblem
  private case object LogAlreadyExists extends CommitProblem

  def apply[F[_]: Async: Logger](
      request: CommitRequest
  )(
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      replicaCount: ReplicaCount,
      requestStateManager: RequestStateManager[F],
      currentViewRef: CurrentViewRef[F],
      storageApi: StorageApi[F],
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
      canInsert <- storageApi
        .getCommitMessages(request.viewNumber, request.sequenceNumber)
        .map(x =>
          x.find(y =>
            Encoding.encodeToHex(y.digest.toByteArray()) == Encoding
              .encodeToHex(request.digest.toByteArray())
          ).isEmpty
        )
      _ <- Async[F].raiseUnless(canInsert)(
        LogAlreadyExists
      )
      _ <- storageApi.insertCommitMessage(request)
      isCommited <- isCommitted[F](
        request.viewNumber,
        request.sequenceNumber
      )
      _ <- requestStateManager.processEvent(Commited(request)).whenA(isCommited)
    } yield ()).handleErrorWith {
      _ match {
        case InvalidPrepareSignature =>
          error"Invalid commit signature"
        case InvalidView =>
          error"Invalid view number"
        case InvalidWatermark =>
          error"Invalid watermark"
      }
    }
  }
}

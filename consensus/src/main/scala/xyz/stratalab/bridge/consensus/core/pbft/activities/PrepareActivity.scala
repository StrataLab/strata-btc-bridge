package xyz.stratalab.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import co.topl.brambl.utils.Encoding
import xyz.stratalab.bridge.consensus.core.pbft.PBFTInternalEvent
import xyz.stratalab.bridge.consensus.core.pbft.Prepared
import xyz.stratalab.bridge.consensus.core.pbft.RequestIdentifier
import xyz.stratalab.bridge.consensus.pbft.PrepareRequest
import xyz.stratalab.bridge.consensus.shared.persistence.StorageApi
import xyz.stratalab.bridge.shared.ClientId
import xyz.stratalab.bridge.shared.ReplicaCount
import xyz.stratalab.bridge.shared.implicits._
import org.typelevel.log4cats.Logger

import java.security.PublicKey
import xyz.stratalab.bridge.consensus.core.pbft.ViewManager

object PrepareActivity {

  private sealed trait PrepareProblem extends Throwable
  private case object InvalidPrepareSignature extends PrepareProblem
  private case object InvalidView extends PrepareProblem
  private case object InvalidWatermark extends PrepareProblem
  private case object LogAlreadyExists extends PrepareProblem
  import cats.implicits._

  def apply[F[_]: Async: Logger](
      request: PrepareRequest
  )(
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      viewManager: ViewManager[F],
      storageApi: StorageApi[F],
      replicaCount: ReplicaCount
  ): F[Option[PBFTInternalEvent]] = {
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
        .getPrepareMessages(request.viewNumber, request.sequenceNumber)
        .map(x =>
          x.find(y =>
            Encoding.encodeToHex(y.digest.toByteArray()) == Encoding
              .encodeToHex(request.digest.toByteArray())
          ).isEmpty
        )
      _ <- Async[F].raiseUnless(canInsert)(
        LogAlreadyExists
      )
      _ <- storageApi.insertPrepareMessage(request)
      isPrepared <- isPrepared[F](
        request.viewNumber,
        request.sequenceNumber
      )
      somePrePrepareMessage <-
        if (isPrepared)
          storageApi
            .getPrePrepareMessage(request.viewNumber, request.sequenceNumber)
        else
          Async[F].pure(None)
    } yield Option.when(isPrepared)(
      Prepared(
        RequestIdentifier(
          ClientId(
            somePrePrepareMessage.flatMap(_.payload).get.clientNumber
          ),
          somePrePrepareMessage.flatMap(_.payload).get.timestamp
        ),
        request
      ): PBFTInternalEvent
    )).handleErrorWith {
      _ match {
        case InvalidPrepareSignature =>
          error"Invalid Prepare signature" >> none[PBFTInternalEvent]
            .pure[F]
        case InvalidView =>
          error"Invalid view number" >> none[PBFTInternalEvent]
            .pure[F]
        case InvalidWatermark =>
          error"Invalid watermark" >> none[PBFTInternalEvent]
            .pure[F]
        case LogAlreadyExists =>
          warn"Prepare message already exists for this sequence number" >> none[
            PBFTInternalEvent
          ]
            .pure[F]
      }
    }
  }

}

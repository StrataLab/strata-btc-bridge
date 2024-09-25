package xyz.stratalab.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import cats.implicits._
import co.topl.brambl.utils.Encoding
import xyz.stratalab.bridge.consensus.core.pbft.Commited
import xyz.stratalab.bridge.consensus.core.pbft.PBFTInternalEvent
import xyz.stratalab.bridge.consensus.core.pbft.RequestIdentifier
import xyz.stratalab.bridge.consensus.pbft.CommitRequest
import xyz.stratalab.bridge.consensus.shared.persistence.StorageApi
import xyz.stratalab.bridge.shared.ClientId
import xyz.stratalab.bridge.shared.ReplicaCount
import xyz.stratalab.bridge.shared.implicits._
import org.typelevel.log4cats.Logger

import java.security.PublicKey
import xyz.stratalab.bridge.consensus.core.pbft.ViewManager

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
      replicaCount: ReplicaCount,
      viewManager: ViewManager[F],
      storageApi: StorageApi[F]
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
      somePrePrepareMessage <-
        if (isCommited)
          storageApi
            .getPrePrepareMessage(request.viewNumber, request.sequenceNumber)
        else
          Async[F].pure(None)
    } yield Option.when(isCommited)(
      Commited(
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
          error"Invalid commit signature" >> none[PBFTInternalEvent]
            .pure[F]
        case InvalidView =>
          error"Invalid view number in commit message" >> none[
            PBFTInternalEvent
          ]
            .pure[F]
        case InvalidWatermark =>
          error"Invalid watermark in commit message" >> none[PBFTInternalEvent]
            .pure[F]
        case LogAlreadyExists =>
          error"Log already exists for this commit message" >> none[
            PBFTInternalEvent
          ]
            .pure[F]
      }
    }
  }
}

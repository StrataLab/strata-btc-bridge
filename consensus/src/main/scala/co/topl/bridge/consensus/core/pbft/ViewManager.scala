package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import co.topl.bridge.consensus.pbft.Pm
import co.topl.bridge.consensus.pbft.ViewChangeRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId

trait ViewManager[F[_]] {

  def currentView: F[Long]

  def createViewChangeRequest(): F[ViewChangeRequest]

}

object ViewManagerImpl {
  import cats.implicits._

  def make[F[_]: Async](
      storageApi: StorageApi[F],
      checkpointManager: CheckpointManager[F]
  )(implicit
      replica: ReplicaId,
      replicaCount: ReplicaCount
  ): F[ViewManager[F]] = {
    for {
      currentViewRef <- Ref.of[F, Long](0L)
    } yield new ViewManager[F] {

      override def currentView: F[Long] = currentViewRef.get

      override def createViewChangeRequest(): F[ViewChangeRequest] = {
        for {
          view <- currentViewRef.get
          latestStableCheckpoint <- checkpointManager.latestStableCheckpoint
          prePrepareRequests <- storageApi.getPrePrepareMessagesFromSeqNumber(
            view,
            latestStableCheckpoint.sequenceNumber
          )
          prepareRequests <- (prePrepareRequests
            .map { prePrepareRequest =>
              storageApi.getPrepareMessages(
                view,
                prePrepareRequest.sequenceNumber
              )
            })
            .toList
            .sequence
            .map(_.flatten)
          viewChangeRequest = ViewChangeRequest(
            newViewNumber = view + 1,
            lastStableCheckpoinSeqNumber =
              latestStableCheckpoint.sequenceNumber,
            checkpoints = latestStableCheckpoint.certificates.toList.map(_._2),
            replicaId = replica.id,
            pms = prePrepareRequests
              .filter(prePrepareRequest =>
                prepareRequests
                  .filter(x =>
                    x.sequenceNumber == prePrepareRequest.sequenceNumber &&
                      x.digest.toByteArray
                        .sameElements(prePrepareRequest.digest.toByteArray())
                  )
                  .length >= 2 * replicaCount.maxFailures
              )
              .map(prePrepareRequest =>
                Pm(
                  Some(prePrepareRequest),
                  prepareRequests
                    .filter(x =>
                      x.sequenceNumber == prePrepareRequest.sequenceNumber &&
                        x.digest.toByteArray
                          .sameElements(prePrepareRequest.digest.toByteArray())
                    )
                )
              )
          )
        } yield viewChangeRequest
      }
    }
  }
}

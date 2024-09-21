package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Async
import cats.effect.std.Queue
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.pbft.activities.CommitActivity
import co.topl.bridge.consensus.core.pbft.activities.PrePrepareActivity
import co.topl.bridge.consensus.core.pbft.activities.PrepareActivity
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaCount
import org.typelevel.log4cats.Logger

import java.security.PublicKey
import co.topl.bridge.consensus.pbft.ViewChangeRequest
import co.topl.bridge.consensus.core.pbft.activities.ViewChangeActivity

trait PBFTRequestPreProcessor[F[_]] {

  def preProcessRequest(request: PrePrepareRequest): F[Unit]
  def preProcessRequest(request: PrepareRequest): F[Unit]
  def preProcessRequest(request: CommitRequest): F[Unit]
  def preProcessRequest(request: ViewChangeRequest): F[Unit]

}

object PBFTRequestPreProcessorImpl {

  def make[F[_]: Async: Logger](
      queue: Queue[F, PBFTInternalEvent],
      viewManager: ViewManager[F],
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      requestTimerManager: RequestTimerManager[F],
      requestStateManager: RequestStateManager[F],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      storageApi: StorageApi[F],
      replicaCount: ReplicaCount
  ): PBFTRequestPreProcessor[F] = new PBFTRequestPreProcessor[F] {

    import org.typelevel.log4cats.syntax._
    import cats.implicits._

    private implicit val viewManagerImplicit: ViewManager[F] = viewManager

    override def preProcessRequest(request: PrePrepareRequest): F[Unit] = {
      for {
        timerExpired <- requestTimerManager.hasExpiredTimer()
        _ <-
          if (timerExpired)
            error"Cannot process pre-prepare request, timer expired"
          else
            for {
              someEvent <- PrePrepareActivity(
                request
              )(replicaKeysMap)
              _ <- someEvent.map(evt => queue.offer(evt)).sequence
            } yield ()
      } yield ()
    }

    override def preProcessRequest(request: PrepareRequest): F[Unit] =
      for {
        timerExpired <- requestTimerManager.hasExpiredTimer()
        _ <-
          if (timerExpired)
            error"Cannot process prepare request, timer expired"
          else
            for {
              someEvent <- PrepareActivity(
                request
              )(replicaKeysMap)
              _ <- someEvent.map(evt => queue.offer(evt)).sequence
            } yield ()
      } yield ()
    override def preProcessRequest(request: CommitRequest): F[Unit] =
      for {
        timerExpired <- requestTimerManager.hasExpiredTimer()
        _ <-
          if (timerExpired)
            error"Cannot process commit request, timer expired"
          else
            for {
              someEvent <-
                CommitActivity(
                  request
                )(replicaKeysMap)
              _ <- someEvent.map(evt => queue.offer(evt)).sequence
            } yield ()
      } yield ()

    override def preProcessRequest(request: ViewChangeRequest): F[Unit] = {
      ViewChangeActivity(request, replicaKeysMap)
    }

  }
}

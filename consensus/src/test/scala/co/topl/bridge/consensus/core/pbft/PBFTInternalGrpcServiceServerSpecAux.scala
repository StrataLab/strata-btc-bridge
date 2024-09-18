package co.topl.bridge.consensus.core.pbft

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.shared.utils.ConfUtils._
import co.topl.bridge.stubs.BaseRequestStateManager
import co.topl.bridge.stubs.BaseRequestTimerManager
import org.typelevel.log4cats.Logger

trait PBFTInternalGrpcServiceServerSpecAux extends SampleData {

  def createSimpleInternalServer(
      currentViewRef: Ref[IO, Long]
  )(implicit
      storageApi: StorageApi[IO],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      logger: Logger[IO]
  ) = {
    implicit val iCurrentViewRef = new CurrentViewRef(currentViewRef)

    implicit val rquestStateManager = new BaseRequestStateManager()
    implicit val requestTimerManager = new BaseRequestTimerManager()
    for {
      checkpointManager <- CheckpointManagerImpl.make[IO]().toResource
      replicaKeysMap <- createReplicaPublicKeyMap[IO](conf).toResource
      lowAndHigh <- Ref.of[IO, (Long, Long)]((0L, 0L)).toResource
      queue <- Queue.unbounded[IO, PBFTInternalEvent].toResource
    } yield {
      implicit val iCheckpointManager = checkpointManager
      implicit val pbftReqProcessor = PBFTRequestPreProcessorImpl
        .make[IO](
          queue,
          replicaKeysMap
        )
      implicit val watermarkRef = new WatermarkRef(lowAndHigh)
      PBFTInternalGrpcServiceServer.pbftInternalGrpcServiceServerAux[IO](
        replicaKeysMap
      )
    }
  }
}

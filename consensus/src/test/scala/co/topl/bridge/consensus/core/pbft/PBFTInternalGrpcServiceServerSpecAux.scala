package co.topl.bridge.consensus.core.pbft

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.StableCheckpoint
import co.topl.bridge.consensus.core.StableCheckpointRef
import co.topl.bridge.consensus.core.StateSnapshotRef
import co.topl.bridge.consensus.core.UnstableCheckpointsRef
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.shared.utils.ConfUtils._
import co.topl.bridge.stubs.BaseRequestStateManager
import org.typelevel.log4cats.Logger

import statemachine.PBFTState
import cats.effect.std.Queue
import co.topl.bridge.stubs.BaseRequestTimerManager

trait PBFTInternalGrpcServiceServerSpecAux extends SampleData {

  def createSimpleInternalServer(
      currentViewRef: Ref[IO, Long],
  )(implicit
      storageApi: StorageApi[IO],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      logger: Logger[IO]
  ) = {
    implicit val iCurrentViewRef = new CurrentViewRef(currentViewRef)

    implicit val rquestStateManager = new BaseRequestStateManager()
    implicit val requestTimerManager = new BaseRequestTimerManager()
    for {
      replicaKeysMap <- createReplicaPublicKeyMap[IO](conf).toResource
      lowAndHigh <- Ref.of[IO, (Long, Long)]((0L, 0L)).toResource
      state <- Ref
        .of[IO, (Long, String, Map[String, PBFTState])]((0L, "", Map.empty))
        .toResource
      stableCheckpoint <- Ref
        .of[IO, StableCheckpoint](StableCheckpoint(100L, Map.empty, Map.empty))
        .toResource
      queue <- Queue.unbounded[IO, PBFTInternalEvent].toResource
      unstableCheckpoint <- Ref
        .of[
          IO,
          Map[
            (Long, String),
            Map[Int, CheckpointRequest]
          ]
        ](Map.empty)
        .toResource
    } yield {
      implicit val pbftReqProcessor = PBFTRequestPreProcessorImpl
        .make[IO](
          queue,
          replicaKeysMap
        )
      implicit val watermarkRef = new WatermarkRef(lowAndHigh)
      implicit val stateSnapshotRef = StateSnapshotRef[IO](state)
      implicit val stableCheckpointRef =
        StableCheckpointRef[IO](stableCheckpoint)
      implicit val unstableCheckpointsRef = UnstableCheckpointsRef[IO](
        unstableCheckpoint
      )
      PBFTInternalGrpcServiceServer.pbftInternalGrpcServiceServerAux[IO](
        replicaKeysMap
      )
    }
  }
}

package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits._
import co.topl.bridge.consensus.core.KWatermark
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.core.pbft.activities.CheckpointActivity
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.Empty
import co.topl.bridge.shared.ReplicaCount
import io.grpc.Metadata
import io.grpc.ServerServiceDefinition
import org.typelevel.log4cats.Logger

import java.security.PublicKey
import co.topl.bridge.consensus.pbft.ViewChangeRequest
import co.topl.bridge.consensus.pbft.NewViewRequest

object PBFTInternalGrpcServiceServer {

  def pbftInternalGrpcServiceServerAux[F[_]: Async: Logger](
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      checkpointManager: CheckpointManager[F],
      pbftReqProcessor: PBFTRequestPreProcessor[F],
      watermarkRef: WatermarkRef[F],
      kWatermark: KWatermark,
      storageApi: StorageApi[F],
      replicaCount: ReplicaCount
  ) = new PBFTInternalServiceFs2Grpc[F, Metadata] {

    override def newView(request: NewViewRequest, ctx: Metadata): F[Empty] = ???

    override def viewChange(
        request: ViewChangeRequest,
        ctx: Metadata
    ): F[Empty] = ???

    override def prePrepare(
        request: PrePrepareRequest,
        ctx: Metadata
    ): F[Empty] =
      pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

    override def prepare(
        request: PrepareRequest,
        ctx: Metadata
    ): F[Empty] = pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

    override def checkpoint(
        request: CheckpointRequest,
        ctx: Metadata
    ): F[Empty] = {
      CheckpointActivity(
        replicaKeysMap,
        request
      )
    }

    override def commit(request: CommitRequest, ctx: Metadata): F[Empty] =
      pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

  }

  def pbftInternalGrpcServiceServer[F[_]: Async: Logger](
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      checkpointManager: CheckpointManager[F],
      pbftReqProcessor: PBFTRequestPreProcessor[F],
      watermarkRef: WatermarkRef[F],
      kWatermark: KWatermark,
      storageApi: StorageApi[F],
      replicaCount: ReplicaCount
  ): Resource[F, ServerServiceDefinition] =
    PBFTInternalServiceFs2Grpc.bindServiceResource(
      pbftInternalGrpcServiceServerAux(
        replicaKeysMap
      )
    )
}

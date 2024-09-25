package co.topl.consensus.core

import cats.effect.kernel.Async
import xyz.stratalab.bridge.consensus.pbft.CommitRequest
import xyz.stratalab.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import xyz.stratalab.bridge.consensus.pbft.PrePrepareRequest
import xyz.stratalab.bridge.consensus.pbft.PrepareRequest
import xyz.stratalab.bridge.shared.Empty
import xyz.stratalab.bridge.shared.ReplicaNode
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import xyz.stratalab.bridge.consensus.pbft.CheckpointRequest
import xyz.stratalab.bridge.consensus.pbft.ViewChangeRequest
import xyz.stratalab.bridge.consensus.pbft.NewViewRequest

trait PBFTInternalGrpcServiceClient[F[_]] {

  def prePrepare(
      request: PrePrepareRequest
  ): F[Empty]

  def prepare(
      request: PrepareRequest
  ): F[Empty]

  def commit(
      request: CommitRequest
  ): F[Empty]

  def checkpoint(
      request: CheckpointRequest
  ): F[Empty]

  def viewChange(
      request: ViewChangeRequest
  ): F[Empty]

  def newView(
      request: NewViewRequest
  ): F[Empty]

}

object PBFTInternalGrpcServiceClientImpl {

  import cats.implicits._

  def make[F[_]: Async: Logger](
      replicaNodes: List[ReplicaNode[F]]
  ) = {
    for {
      idBackupMap <- (for {
        replicaNode <- replicaNodes
      } yield {
        for {
          channel <-
            (if (replicaNode.backendSecure)
               ManagedChannelBuilder
                 .forAddress(replicaNode.backendHost, replicaNode.backendPort)
                 .useTransportSecurity()
             else
               ManagedChannelBuilder
                 .forAddress(replicaNode.backendHost, replicaNode.backendPort)
                 .usePlaintext()).resource[F]
          consensusClient <- PBFTInternalServiceFs2Grpc.stubResource(
            channel
          )
        } yield (replicaNode.id -> consensusClient)
      }).sequence
      backupMap = idBackupMap.toMap
    } yield new PBFTInternalGrpcServiceClient[F] {

      override def viewChange(request: ViewChangeRequest): F[Empty] =
        for {
          _ <- trace"Sending CommitRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.viewChange(request, new Metadata())
          }
        } yield Empty()

      override def commit(request: CommitRequest): F[Empty] =
        for {
          _ <- trace"Sending CommitRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.commit(request, new Metadata())
          }
        } yield Empty()

      override def prePrepare(request: PrePrepareRequest): F[Empty] =
        for {
          _ <- trace"Sending PrePrepareRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.prePrepare(request, new Metadata())
          }
        } yield Empty()

      override def prepare(
          request: PrepareRequest
      ): F[Empty] = {
        for {
          _ <- trace"Sending PrepareRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.prepare(request, new Metadata())
          }
        } yield Empty()
      }

      override def checkpoint(
          request: CheckpointRequest
      ): F[Empty] = for {
        _ <- trace"Sending Checkpoint to all replicas"
        _ <- backupMap.toList.traverse { case (_, backup) =>
          backup.checkpoint(request, new Metadata())
        }
      } yield Empty()

      override def newView(
          request: NewViewRequest
      ): F[Empty] = for {
        _ <- trace"Sending NewViewRequest to all replicas"
        _ <- backupMap.toList.traverse { case (_, backup) =>
          backup.newView(request, new Metadata())
        }
      } yield Empty()

    }
  }
}

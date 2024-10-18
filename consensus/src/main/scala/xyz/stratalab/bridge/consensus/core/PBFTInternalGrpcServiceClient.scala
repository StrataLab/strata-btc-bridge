package xyz.stratalab.consensus.core

import cats.effect.kernel._
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import xyz.stratalab.bridge.consensus.pbft.{
  CheckpointRequest,
  CommitRequest,
  NewViewRequest,
  PBFTInternalServiceFs2Grpc,
  PrePrepareRequest,
  PrepareRequest,
  ViewChangeRequest
}
import cats.Parallel
import xyz.stratalab.bridge.shared.{BridgeCryptoUtils, Empty, PBFTInternalGrpcServiceClientRetryConfigImpl, ReplicaNode}

import java.security.KeyPair
import scala.concurrent.duration._

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

  def make[F[_]: Parallel: Async: Logger](
    keyPair:      KeyPair,
    replicaNodes: List[ReplicaNode[F]]
  )(implicit pbftInternalConfig: PBFTInternalGrpcServiceClientRetryConfigImpl) =
    for {
      idBackupMap <- (for {
        replicaNode <- replicaNodes
      } yield for {
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
      } yield (replicaNode.id -> consensusClient)).sequence
      backupMap = idBackupMap.toMap
    } yield new PBFTInternalGrpcServiceClient[F] {

      import xyz.stratalab.bridge.shared.implicits._

      def retryWithBackoff[A](
        operation:     => F[A],
        initialDelay:  FiniteDuration,
        maxRetries:    Int,
        operationName: String,
        defaultValue:  => A
      )(implicit F: Temporal[F]): F[A] =
        operation.handleErrorWith { error =>
          if (maxRetries > 0) {
            F.sleep(initialDelay) >> {
              trace"Retrying $operationName. Retries left: $maxRetries, Next delay: ${initialDelay * 2}" >>
              retryWithBackoff(operation, initialDelay * 2, maxRetries - 1, operationName, defaultValue)
            }
          } else {
            trace"Max retries reached for $operationName. Error: ${error.getMessage}" >>
            F.pure(defaultValue)
          }
        }

      override def viewChange(request: ViewChangeRequest): F[Empty] =
        for {
          _ <- trace"Sending ViewChange to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.viewChange(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.getInitialDelay,
              pbftInternalConfig.getMaxRetries,
              "View Change",
              Empty()
            )
          }
        } yield Empty()

      override def commit(request: CommitRequest): F[Empty] =
        for {
          _ <- trace"Sending CommitRequest to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.commit(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.getInitialDelay,
              pbftInternalConfig.getMaxRetries,
              "Commit",
              Empty()
            ).handleErrorWith { _ =>
              Async[F].pure(Empty())
            }
          }
        } yield Empty()

      override def prePrepare(request: PrePrepareRequest): F[Empty] =
        for {
          _ <- trace"Sending PrePrepareRequest to all replicas"
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.prePrepare(
                request,
                new Metadata()
              ),
              pbftInternalConfig.getInitialDelay,
              pbftInternalConfig.getMaxRetries,
              "Pre Prepare",
              Empty()
            )
          }
        } yield Empty()

      override def prepare(
        request: PrepareRequest
      ): F[Empty] =
        for {
          _ <- trace"Sending PrepareRequest to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.prepare(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.getInitialDelay,
              pbftInternalConfig.getMaxRetries,
              "Prepare",
              Empty()
            )
          }
        } yield Empty()

      override def checkpoint(
        request: CheckpointRequest
      ): F[Empty] =
        for {
          _ <- trace"Sending Checkpoint to all replicas"
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.checkpoint(
                request,
                new Metadata()
              ),
              pbftInternalConfig.getInitialDelay,
              pbftInternalConfig.getMaxRetries,
              "Checkpoint",
              Empty()
            )
          }
        } yield Empty()

      override def newView(
        request: NewViewRequest
      ): F[Empty] =
        for {
          _ <- trace"Sending NewViewRequest to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.newView(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.getInitialDelay,
              pbftInternalConfig.getMaxRetries,
              "new View",
              Empty()
            )
          }
        } yield Empty()
    }
}

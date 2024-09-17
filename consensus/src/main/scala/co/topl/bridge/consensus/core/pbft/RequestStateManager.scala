package co.topl.bridge.consensus.core.pbft

import co.topl.bridge.consensus.pbft.PrePrepareRequest
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ReplicaId
import java.security.KeyPair
import com.google.protobuf.ByteString
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.shared.StateMachineRequest

case class RequestSMIdentifier(
    viewNumber: Long,
    sequenceNumber: Long
)

sealed abstract class StateMachineEvent(
    val viewNumber: Long,
    val sequenceNumber: Long
)

case class PrePreparedInserted(
    request: PrePrepareRequest
) extends StateMachineEvent(
      viewNumber = request.viewNumber,
      sequenceNumber = request.sequenceNumber
    )
case class Prepared(
    request: PrepareRequest
) extends StateMachineEvent(
      viewNumber = request.viewNumber,
      sequenceNumber = request.sequenceNumber
    )
case class Commited(
    request: CommitRequest
) extends StateMachineEvent(
      viewNumber = request.viewNumber,
      sequenceNumber = request.sequenceNumber
    )
trait RequestStateManager[F[_]] {

  def createStateMachine(viewNumber: Long, sequenceNumber: Long): F[Unit]

  def processEvent(
      event: StateMachineEvent
  )(implicit
      replica: ReplicaId,
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ): F[Unit]

}

sealed trait RequestState

case object PrePreparePhase extends RequestState
case class PreparePhase(stateMachineRequest: StateMachineRequest)
    extends RequestState
case class CommitPhase(
    stateMachineRequest: StateMachineRequest
) extends RequestState

object RequestStateMachineTransitionRelation {

  import co.topl.bridge.shared.implicits._
  import cats.implicits._

  private def prepare[F[_]: Async](
      keyPair: KeyPair,
      request: PrePrepareRequest
  )(implicit
      replica: ReplicaId,
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) = {
    val prepareRequest = PrepareRequest(
      viewNumber = request.viewNumber,
      sequenceNumber = request.sequenceNumber,
      digest = request.digest,
      replicaId = replica.id
    )
    for {
      signedBytes <- BridgeCryptoUtils.signBytes[F](
        keyPair.getPrivate(),
        prepareRequest.signableBytes
      )
      prepareRequestSigned = prepareRequest.withSignature(
        ByteString.copyFrom(signedBytes)
      )
      _ <- pbftProtocolClientGrpc.prepare(
        prepareRequestSigned
      )
    } yield ()
  }

  private def commit[F[_]: Async](keyPair: KeyPair, request: PrepareRequest)(
      implicit pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) = {
    val commitRequest = CommitRequest(
      viewNumber = request.viewNumber,
      sequenceNumber = request.sequenceNumber,
      digest = request.digest,
      replicaId = request.replicaId
    )
    for {
      signedBytes <- BridgeCryptoUtils.signBytes[F](
        keyPair.getPrivate(),
        commitRequest.signableBytes
      )
      _ <- pbftProtocolClientGrpc.commit(
        commitRequest.withSignature(
          ByteString.copyFrom(signedBytes)
        )
      )
    } yield ()
  }

  def transition[F[_]: Async](
      keyPair: KeyPair
  )(requestState: RequestState, event: StateMachineEvent)(implicit
      replica: ReplicaId,
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ): (RequestState, F[Unit]) = {
    (requestState, event) match {
      case (PrePreparePhase, PrePreparedInserted(request)) =>
        (PreparePhase(request.payload.get), prepare[F](keyPair, request))
      case (PreparePhase(smRequest), Prepared(request)) =>
        (CommitPhase(smRequest), commit[F](keyPair, request))
      case (CommitPhase(smRequest), PrePreparedInserted(_)) =>
        (PrePreparePhase, ???)
    }
  }
}

object RequestStateManager {

  import cats.implicits._

  def make[F[_]: Async](keyPair: KeyPair): F[RequestStateManager[F]] =
    for {
      map <- Ref.of[F, Map[RequestSMIdentifier, RequestState]](Map.empty)
    } yield new RequestStateManager[F] {
      override def createStateMachine(
          viewNumber: Long,
          sequenceNumber: Long
      ): F[Unit] = {
        map.update(
          _ + (RequestSMIdentifier(
            viewNumber,
            sequenceNumber
          ) -> PrePreparePhase)
        )
      }

      def processEvent(
          event: StateMachineEvent
      )(implicit
          replica: ReplicaId,
          pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
      ): F[Unit] = for {
        _ <- map.flatModify { map =>
          val currentState =
            map(RequestSMIdentifier(event.viewNumber, event.sequenceNumber))
          val (newState, action) =
            RequestStateMachineTransitionRelation.transition[F](keyPair)(
              currentState,
              event
            )
          (
            map.updated(
              RequestSMIdentifier(event.viewNumber, event.sequenceNumber),
              newState
            ),
            action
          )
        }

      } yield ()
    }
}

package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Async
import cats.effect.kernel.Outcome
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import co.topl.bridge.consensus.core.pbft.statemachine.BridgeStateMachineExecutionManager
import xyz.stratalab.bridge.consensus.pbft.CommitRequest
import xyz.stratalab.bridge.consensus.pbft.PrePrepareRequest
import xyz.stratalab.bridge.consensus.pbft.PrepareRequest
import xyz.stratalab.bridge.shared.BridgeCryptoUtils
import xyz.stratalab.bridge.shared.ClientId
import xyz.stratalab.bridge.shared.ReplicaId
import xyz.stratalab.bridge.shared.StateMachineRequest
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import com.google.protobuf.ByteString

import java.security.KeyPair

trait RequestStateManager[F[_]] {

  def createStateMachine(requestIdentifier: RequestIdentifier): F[Unit]

  def startProcessingEvents(): Resource[F, F[Outcome[F, Throwable, Unit]]]

}

sealed trait RequestState

case object PrePreparePhase extends RequestState
case class PreparePhase(stateMachineRequest: StateMachineRequest)
    extends RequestState
case class CommitPhase(
    stateMachineRequest: StateMachineRequest
) extends RequestState

case object Completed extends RequestState

object RequestStateMachineTransitionRelation {

  import xyz.stratalab.bridge.shared.implicits._
  import cats.implicits._

  private def prepare[F[_]: Async](
      keyPair: KeyPair,
      request: PrePrepareRequest
  )(implicit
      replica: ReplicaId,
      requestTimerManager: RequestTimerManager[F],
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) = {
    val prepareRequest = PrepareRequest(
      viewNumber = request.viewNumber,
      sequenceNumber = request.sequenceNumber,
      digest = request.digest,
      replicaId = replica.id
    )
    for {
      _ <- requestTimerManager.startTimer(
        RequestIdentifier(
          ClientId(request.payload.get.clientNumber),
          request.payload.get.timestamp
        )
      )
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

  private def commit[F[_]: Async](
      keyPair: KeyPair,
      request: PrepareRequest
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
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

  private def complete[F[_]: Async](
      smRequest: StateMachineRequest,
      rmOp: F[Unit]
  )(implicit
      requestTimerManager: RequestTimerManager[F],
      bridgeStateMachineExecutionManager: BridgeStateMachineExecutionManager[F]
  ) = {
    for {
      _ <- bridgeStateMachineExecutionManager.executeRequest(smRequest)
      _ <- requestTimerManager.clearTimer(
        RequestIdentifier(
          ClientId(smRequest.clientNumber),
          smRequest.timestamp
        )
      )
      _ <- rmOp
    } yield ()
  }

  private def viewChange[F[_]: Async](
      keyPair: KeyPair
  )(implicit
      viewManager: ViewManager[F],
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) = {
    for {
      evt <- viewManager.createViewChangeRequest()
      signedBytes <- BridgeCryptoUtils.signBytes[F](
        keyPair.getPrivate(),
        evt.signableBytes
      )
      evtSigned = evt.withSignature(
        ByteString.copyFrom(signedBytes)
      )
      _ <- pbftProtocolClientGrpc.viewChange(evtSigned)
    } yield ()
  }

  def transition[F[_]: Async](
      keyPair: KeyPair,
      rmOp: F[Unit]
  )(requestState: RequestState, event: PBFTInternalEvent)(implicit
      replica: ReplicaId,
      viewManager: ViewManager[F],
      requestTimerManager: RequestTimerManager[F],
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      bridgeStateMachineExecutionManager: BridgeStateMachineExecutionManager[F]
  ): (Option[RequestState], F[Unit]) = {
    (requestState, event) match {
      case (PrePreparePhase, PrePreparedInserted(request)) =>
        (Some(PreparePhase(request.payload.get)), prepare[F](keyPair, request))
      case (PreparePhase(smRequest), Prepared(_, request)) =>
        (Some(CommitPhase(smRequest)), commit[F](keyPair, request))
      case (CommitPhase(smRequest), Commited(_, _)) =>
        (Some(Completed), complete(smRequest, rmOp))
      case (_, PBFTTimeoutEvent(_)) =>
        // if there is a timeout event, then we remove only
        // the request from the state machine
        // we do not touch other request because there might be other
        // messages that are still in the pipeline
        // however, the pipeline will stop processing the messages so
        // eventually there will not be any more changes.
        (
          Some(Completed),
          viewChange(keyPair)
        )
      case (_, _) =>
        (None, Async[F].unit)
    }
  }
}

object RequestStateManagerImpl {

  import cats.implicits._

  def make[F[_]: Async](
      keyPair: KeyPair,
      viewManager: ViewManager[F],
      queue: Queue[F, PBFTInternalEvent],
      requestTimerManager: RequestTimerManager[F],
      bridgeStateMachineExecutionManager: BridgeStateMachineExecutionManager[
        F
      ]
  )(implicit
      replica: ReplicaId,
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ): F[RequestStateManager[F]] = {

    implicit val iViewManager = viewManager

    implicit val iBridgeStateMachineExecutionManager =
      bridgeStateMachineExecutionManager
    implicit val iRequestTimerManager = requestTimerManager
    for {
      state <- Ref.of[F, Map[RequestIdentifier, RequestState]](Map.empty)
    } yield new RequestStateManager[F] {

      override def startProcessingEvents()
          : Resource[F, F[Outcome[F, Throwable, Unit]]] =
        Async[F].background(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .evalMap(event => processEvent(event))
            .compile
            .drain
        )

      override def createStateMachine(
          requestIdentifier: RequestIdentifier
      ): F[Unit] = {
        state.update(
          _ + (requestIdentifier -> PrePreparePhase)
        )
      }

      private def processEvent(
          event: PBFTInternalEvent
      ): F[Unit] = for {
        _ <- state.flatModify { map =>
          val identifier = event.requestIdentifier
          val someCurrentState = map.get(identifier)
          someCurrentState match {
            case None =>
              (map, Async[F].unit)
            case Some(currentState) =>
              val (newState, action) =
                RequestStateMachineTransitionRelation
                  .transition[F](
                    keyPair,
                    state.update(_ - identifier)
                  )(
                    currentState,
                    event
                  )
              (
                map.updated(
                  identifier,
                  newState.getOrElse(currentState)
                ),
                action
              )
          }
        }

      } yield ()
    }
  }
}

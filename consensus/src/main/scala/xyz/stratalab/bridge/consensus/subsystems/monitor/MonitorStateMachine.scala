package xyz.stratalab.bridge.consensus.subsystems.monitor

import cats.effect.kernel.{Async, Ref, Sync}
import cats.implicits._
import co.topl.brambl.models.{GroupId, SeriesId}
import org.typelevel.log4cats.Logger
import xyz.stratalab.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  BTCWaitExpirationTime,
  PeginSessionInfo,
  StrataConfirmationThreshold,
  StrataWaitExpirationTime
}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  EndTransition,
  FSMTransitionTo,
  MWaitingForBTCDeposit,
  MonitorTransitionRelation,
  PeginStateMachineState
}
import xyz.stratalab.bridge.shared.{ClientId, SessionId, StateMachineServiceGrpcClient}

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap

trait MonitorStateMachineAlgebra[F[_]] {

  def handleBlockchainEventInContext(
    blockchainEvent: BlockchainEvent
  ): fs2.Stream[F, F[Unit]]

  def innerStateConfigurer(
    sessionEvent: SessionEvent
  ): F[Unit]

}

object MonitorStateMachine {

  def make[F[_]: Async: Logger](
    currentBitcoinNetworkHeight: Ref[F, Int],
    currentStrataNetworkHeight:  Ref[F, Long],
    map:                         ConcurrentHashMap[String, PeginStateMachineState]
  )(implicit
    clientId:                  ClientId,
    consensusClient:           StateMachineServiceGrpcClient[F],
    btcWaitExpirationTime:     BTCWaitExpirationTime,
    toplWaitExpirationTime:    StrataWaitExpirationTime,
    btcRetryThreshold:         BTCRetryThreshold,
    btcConfirmationThreshold:  BTCConfirmationThreshold,
    toplConfirmationThreshold: StrataConfirmationThreshold,
    groupIdIdentifier:         GroupId,
    seriesIdIdentifier:        SeriesId
  ) = new MonitorStateMachineAlgebra[F] {

    import org.typelevel.log4cats.syntax._
    import MonitorTransitionRelation._

    private def updateBTCHeight(
      blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] =
      blockchainEvent match {
        case NewBTCBlock(height) =>
          fs2.Stream(
            for {
              x <- currentBitcoinNetworkHeight.get
              _ <-
                if (height > x)
                  currentBitcoinNetworkHeight.set(height)
                else Sync[F].unit
            } yield ()
          )

        case _ => fs2.Stream.empty
      }

    private def updateStrataHeight(
      blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] =
      blockchainEvent match {
        case NewStrataBlock(height) =>
          fs2.Stream(
            for {
              x <- currentStrataNetworkHeight.get
              _ <- trace"current topl height is $x"
              _ <- trace"Updating topl height to $height"
              _ <-
                if (height > x)
                  currentStrataNetworkHeight.set(height)
                else Sync[F].unit
            } yield ()
          )

        case _ => fs2.Stream.empty
      }

    def handleBlockchainEventInContext(
      blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] = {
      import scala.jdk.CollectionConverters._
      updateStrataHeight(blockchainEvent) ++
      updateBTCHeight(blockchainEvent) ++ (for {
        entry <- fs2.Stream[F, Entry[String, PeginStateMachineState]](
          map.entrySet().asScala.toList: _*
        )
        sessionId = entry.getKey
        currentState = entry.getValue
      } yield {
        implicit val sessionIdImplicit = new SessionId(sessionId)
        handleBlockchainEvent[F](
          currentState,
          blockchainEvent
        )(transitionToEffect[F])
          .orElse(
            Some(
              FSMTransitionTo(
                currentState,
                currentState,
                transitionToEffect(currentState, blockchainEvent)
              )
            )
          )
      }
        .map(x =>
          x match {
            case EndTransition(effect) =>
              info"Session $sessionId ended successfully" >>
              Sync[F].delay(map.remove(sessionId)) >>
              Sync[F]
                .delay(
                  map
                    .remove(sessionId)
                )
                .flatMap(_ => effect.asInstanceOf[F[Unit]])
            case FSMTransitionTo(prevState, nextState, effect) if (prevState != nextState) =>
              info"Transitioning session $sessionId from ${currentState
                  .getClass()
                  .getSimpleName()} to ${nextState.getClass().getSimpleName()}" >>
              processTransition(
                sessionId,
                FSMTransitionTo[F](
                  prevState,
                  nextState,
                  effect.asInstanceOf[F[Unit]]
                )
              )
            case FSMTransitionTo(_, _, _) =>
              Sync[F].unit
          }
        )).collect { case Some(value) =>
        if (blockchainEvent.isInstanceOf[NewBTCBlock])
          debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewBTCBlock].height}" >> value
        else if (blockchainEvent.isInstanceOf[SkippedBTCBlock])
          debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[SkippedBTCBlock].height}" >> value
        else if (blockchainEvent.isInstanceOf[NewStrataBlock])
          debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewStrataBlock].height}" >> value
        else
          debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()}" >> value
      }
    }

    def processTransition(sessionId: String, transition: FSMTransitionTo[F]) =
      Sync[F].delay(map.replace(sessionId, transition.nextState)) >>
      transition.effect

    def innerStateConfigurer(
      sessionEvent: SessionEvent
    ): F[Unit] =
      sessionEvent match {
        case SessionCreated(sessionId, psi: PeginSessionInfo) =>
          info"New session created, waiting for funds at ${psi.escrowAddress}" >>
          currentBitcoinNetworkHeight.get.flatMap(cHeight =>
            Sync[F].delay(
              map.put(
                sessionId,
                MWaitingForBTCDeposit(
                  cHeight,
                  psi.btcPeginCurrentWalletIdx,
                  psi.scriptAsm,
                  psi.escrowAddress,
                  psi.redeemAddress,
                  psi.claimAddress
                )
              )
            )
          )
        case SessionUpdated(_, _: PeginSessionInfo) =>
          Sync[F].unit
      }

  }
}

package xyz.stratalab.bridge.consensus.subsystems.monitor

import cats.effect.kernel.{Async, Ref, Sync}
import cats.implicits._
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
import xyz.stratalab.sdk.models.{GroupId, SeriesId}

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
    currentStrataNetworkHeight:  Ref[F, Long]
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
  ) =
    for {
      map <- Ref.of[F, Map[String, PeginStateMachineState]](Map.empty)
    } yield new MonitorStateMachineAlgebra[F] {

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
      ): fs2.Stream[F, F[Unit]] =
        updateStrataHeight(blockchainEvent) ++
        updateBTCHeight(blockchainEvent) ++ (for {
          entrySet <- fs2.Stream.eval(map.get)
          entry <- fs2.Stream[F, (String, PeginStateMachineState)](
            entrySet.toList: _*
          )
          sessionId = entry._1
          currentState = entry._2
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
                map.update(_ - sessionId) >> effect.asInstanceOf[F[Unit]]
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
            trace"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewBTCBlock].height}" >> value
          else if (blockchainEvent.isInstanceOf[SkippedBTCBlock])
            trace"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[SkippedBTCBlock].height}" >> value
          else if (blockchainEvent.isInstanceOf[NewStrataBlock])
            trace"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewStrataBlock].height}" >> value
          else
            trace"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()}" >> value
        }

      def processTransition(sessionId: String, transition: FSMTransitionTo[F]) =
        map.update(_.updated(sessionId, transition.nextState)) >>
        transition.effect

      def innerStateConfigurer(
        sessionEvent: SessionEvent
      ): F[Unit] =
        sessionEvent match {
          case SessionCreated(sessionId, psi: PeginSessionInfo) =>
            info"New session created, waiting for funds at ${psi.escrowAddress}" >>
            currentBitcoinNetworkHeight.get.flatMap(cHeight =>
              map.update(
                _.+(
                  sessionId ->
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

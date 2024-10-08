package xyz.stratalab.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import cats.implicits._
import com.google.protobuf.ByteString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import xyz.stratalab.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  StrataConfirmationThreshold,
  StrataWaitExpirationTime
}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  MConfirmingBTCDeposit,
  MConfirmingTBTCMint,
  MMintingTBTC,
  MWaitingForBTCDeposit,
  MWaitingForClaim,
  MWaitingForRedemption,
  PeginStateMachineState
}
import xyz.stratalab.bridge.shared.{
  ClientId,
  ConfirmClaimTxOperation,
  ConfirmDepositBTCOperation,
  ConfirmTBTCMintOperation,
  PostClaimTxOperation,
  PostDepositBTCOperation,
  PostRedemptionTxOperation,
  PostTBTCMintOperation,
  SessionId,
  StateMachineServiceGrpcClient,
  TimeoutDepositBTCOperation,
  TimeoutTBTCMintOperation,
  UndoClaimTxOperation,
  UndoDepositBTCOperation,
  UndoTBTCMintOperation
}

trait TransitionToEffect {

  def isAboveConfirmationThresholdBTC(
    currentHeight: Int,
    startHeight:   Int
  )(implicit btcConfirmationThreshold: BTCConfirmationThreshold) =
    currentHeight - startHeight > btcConfirmationThreshold.underlying

  def isAboveConfirmationThresholdStrata(
    currentHeight: Long,
    startHeight:   Long
  )(implicit toplConfirmationThreshold: StrataConfirmationThreshold) =
    currentHeight - startHeight > toplConfirmationThreshold.underlying

  def transitionToEffect[F[_]: Async: Logger](
    currentState:    PeginStateMachineState,
    blockchainEvent: BlockchainEvent
  )(implicit
    clientId:                  ClientId,
    session:                   SessionId,
    consensusClient:           StateMachineServiceGrpcClient[F],
    toplWaitExpirationTime:    StrataWaitExpirationTime,
    btcRetryThreshold:         BTCRetryThreshold,
    toplConfirmationThreshold: StrataConfirmationThreshold,
    btcConfirmationThreshold:  BTCConfirmationThreshold
  ) =
    (blockchainEvent match {
      case SkippedStrataBlock(height) =>
        error"Error the processor skipped Strata block $height"
      case SkippedBTCBlock(height) =>
        error"Error the processor skipped BTC block $height"
      case NewStrataBlock(height) =>
        debug"New Strata block $height"
      case NewBTCBlock(height) =>
        debug"New BTC block $height"
      case _ =>
        Async[F].unit
    }) >>
    ((currentState, blockchainEvent) match {
      case (
            _: MWaitingForBTCDeposit,
            ev: BTCFundsDeposited
          ) =>
        Async[F]
          .start(
            consensusClient.postDepositBTC(
              PostDepositBTCOperation(
                session.id,
                ev.fundsDepositedHeight,
                ev.txId,
                ev.vout,
                ByteString.copyFrom(ev.amount.satoshis.toBigInt.toByteArray)
              )
            )
          )
          .void
      case (
            _: MWaitingForBTCDeposit,
            ev: NewBTCBlock
          ) =>
        Async[F]
          .start(
            consensusClient.timeoutDepositBTC(
              TimeoutDepositBTCOperation(
                session.id,
                ev.height
              )
            )
          )
          .void
      case (
            cs: MConfirmingBTCDeposit,
            ev: NewBTCBlock
          ) =>
        if (isAboveConfirmationThresholdBTC(ev.height, cs.depositBTCBlockHeight)) {
          Async[F]
            .start(
              consensusClient.confirmDepositBTC(
                ConfirmDepositBTCOperation(
                  session.id,
                  ByteString
                    .copyFrom(cs.amount.satoshis.toBigInt.toByteArray),
                  ev.height
                )
              )
            )
            .void
        } else {
          Async[F]
            .start(
              consensusClient.undoDepositBTC(
                UndoDepositBTCOperation(
                  session.id
                )
              )
            )
            .void
        }
      case (
            _: MMintingTBTC,
            be: NodeFundsDeposited
          ) =>
        Async[F]
          .start(
            consensusClient
              .postTBTCMint(
                PostTBTCMintOperation(
                  session.id,
                  be.currentStrataBlockHeight,
                  be.utxoTxId,
                  be.utxoIndex,
                  ByteString.copyFrom(be.amount.amount.value.toByteArray)
                )
              )
          )
          .void
      case (
            _: MMintingTBTC,
            ev: NewBTCBlock
          ) =>
        Async[F]
          .start(
            consensusClient.timeoutTBTCMint(
              TimeoutTBTCMintOperation(
                session.id,
                ev.height
              )
            )
          )
          .void
      case ( // TODO: make sure that by the time we are here, the funds are already locked
            _: MConfirmingTBTCMint,
            _: NewBTCBlock
          ) =>
        Async[F]
          .start(
            consensusClient.undoTBTCMint(
              UndoTBTCMintOperation(
                session.id
              )
            )
          )
          .void
      case (
            cs: MConfirmingTBTCMint,
            be: NewStrataBlock
          ) =>
        if (
          isAboveConfirmationThresholdStrata(
            be.height,
            cs.depositTBTCBlockHeight
          )
        ) {
          Async[F]
            .start(
              consensusClient.confirmTBTCMint(
                ConfirmTBTCMintOperation(
                  session.id,
                  be.height
                )
              )
            )
            .void
        } else if ( // FIXME: check that this is the right time to wait
          toplWaitExpirationTime.underlying < (be.height - cs.depositTBTCBlockHeight)
        )
          Async[F]
            .start(
              consensusClient.timeoutTBTCMint(
                TimeoutTBTCMintOperation(
                  session.id
                )
              )
            )
            .void
        else if (be.height <= cs.depositTBTCBlockHeight)
          Async[F]
            .start(
              consensusClient.undoTBTCMint(
                UndoTBTCMintOperation(
                  session.id
                )
              )
            )
            .void
        else
          Async[F].unit
      case (
            cs: MWaitingForRedemption,
            ev: NodeFundsWithdrawn
          ) =>
        import xyz.stratalab.sdk.syntax._
        Async[F]
          .start(
            consensusClient.postRedemptionTx(
              PostRedemptionTxOperation(
                session.id,
                ev.secret,
                ev.fundsWithdrawnHeight,
                cs.utxoTxId,
                cs.utxoIndex,
                cs.btcTxId,
                cs.btcVout,
                ByteString
                  .copyFrom(int128AsBigInt(ev.amount.amount).toByteArray)
              )
            )
          )
          .void
      case (
            _: MWaitingForRedemption,
            ev: NewStrataBlock
          ) =>
        Async[F]
          .start(
            Async[F]
              .start(
                consensusClient.timeoutTBTCMint(
                  TimeoutTBTCMintOperation(
                    session.id,
                    0,
                    ev.height
                  )
                )
              )
              .void
          )
          .void
      case (
            _: MWaitingForClaim,
            ev: BTCFundsDeposited
          ) =>
        Async[F]
          .start(
            consensusClient.postClaimTx(
              PostClaimTxOperation(
                session.id,
                ev.fundsDepositedHeight,
                ev.txId,
                ev.vout
              )
            )
          )
          .void
      case (
            cs: MConfirmingBTCClaim,
            ev: NewBTCBlock
          ) =>
        if (isAboveConfirmationThresholdBTC(ev.height, cs.claimBTCBlockHeight))
          Async[F]
            .start(
              consensusClient.confirmClaimTx(
                ConfirmClaimTxOperation(
                  session.id,
                  ev.height
                )
              )
            )
            .void
        else
          Async[F]
            .start(
              consensusClient.undoClaimTx(
                UndoClaimTxOperation(
                  session.id
                )
              )
            )
            .void
      case (
            cs: MWaitingForClaim,
            ev: NewBTCBlock
          ) =>
        // if we the someStartBtcBlockHeight is empty, we need to set it
        // if it is not empty, we need to check if the number of blocks since waiting is bigger than the threshold
        cs.someStartBtcBlockHeight match {
          case None =>
            Async[F].unit
          case Some(startBtcBlockHeight) =>
            if (btcRetryThreshold.underlying < (ev.height - startBtcBlockHeight))
              Async[F]
                .start(
                  warn"Confirming claim tx" >>
                  consensusClient.confirmClaimTx(
                    ConfirmClaimTxOperation(
                      session.id,
                      ev.height
                    )
                  )
                )
                .void
            else
              Async[F].unit
        }
      case (_, _) => Async[F].unit
    })

}

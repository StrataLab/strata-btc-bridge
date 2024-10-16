package xyz.stratalab.bridge.consensus.subsystems.monitor

import xyz.stratalab.bridge.consensus.shared.{
  AssetToken,
  BTCWaitExpirationTime,
  StrataConfirmationThreshold,
  StrataWaitExpirationTime
}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  EndTransition,
  FSMTransition,
  FSMTransitionTo,
  MConfirmingTBTCMint,
  MMintingTBTC,
  MWaitingForClaim,
  MWaitingForRedemption,
  PeginStateMachineState
}
import xyz.stratalab.sdk.models.{GroupId, SeriesId}
import xyz.stratalab.sdk.utils.Encoding

trait MonitorMintingStateTransitionRelation extends TransitionToEffect {

  def handleBlockchainEventMinting[F[_]](
    currentState:    MintingState,
    blockchainEvent: BlockchainEvent
  )(
    t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
    btcWaitExpirationTime:     BTCWaitExpirationTime,
    toplWaitExpirationTime:    StrataWaitExpirationTime,
    toplConfirmationThreshold: StrataConfirmationThreshold,
    groupId:                   GroupId,
    seriesId:                  SeriesId
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: MMintingTBTC,
            ev: NewBTCBlock
          ) =>
        if (ev.height - cs.startBTCBlockHeight > btcWaitExpirationTime.underlying)
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: MMintingTBTC,
            be: NodeFundsDeposited
          ) =>
        import xyz.stratalab.sdk.syntax._

        if (
          cs.redeemAddress == be.address &&
          AssetToken(
            Encoding.encodeToBase58(groupId.value.toByteArray),
            Encoding.encodeToBase58(seriesId.value.toByteArray),
            cs.amount.satoshis.toBigInt
          ) == be.amount
        ) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingTBTCMint(
                cs.startBTCBlockHeight,
                be.currentStrataBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                be.utxoTxId,
                be.utxoIndex,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            cs: MConfirmingTBTCMint,
            ev: NewBTCBlock
          ) =>
        if (ev.height - cs.startBTCBlockHeight > btcWaitExpirationTime.underlying)
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else None
      case (
            cs: MConfirmingTBTCMint,
            be: NewStrataBlock
          ) =>
        if (isAboveConfirmationThresholdStrata(be.height, cs.depositTBTCBlockHeight)) {
          import xyz.stratalab.sdk.syntax._
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForRedemption(
                cs.depositTBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.utxoTxId,
                cs.utxoIndex,
                AssetToken(
                  Encoding.encodeToBase58(groupId.value.toByteArray),
                  Encoding.encodeToBase58(seriesId.value.toByteArray),
                  cs.amount.satoshis.toBigInt
                )
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else if (be.height <= cs.depositTBTCBlockHeight) {
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          Some(
            FSMTransitionTo(
              currentState,
              MMintingTBTC(
                cs.startBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )

        } else
          None
      case (
            cs: MWaitingForRedemption,
            ev: NewStrataBlock
          ) =>
        if (toplWaitExpirationTime.underlying < (ev.height - cs.currentTolpBlockHeight))
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: MWaitingForRedemption,
            be: NodeFundsWithdrawn
          ) =>
        if (cs.utxoTxId == be.txId && cs.utxoIndex == be.txIndex) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingRedemption(
                None,
                None,
                be.secret,
                be.fundsWithdrawnHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.utxoTxId,
                cs.utxoIndex,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            cs: MConfirmingRedemption,
            ev: NewStrataBlock
          ) =>
        if (isAboveConfirmationThresholdStrata(ev.height, cs.currentTolpBlockHeight))
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForClaim(
                None,
                cs.secret,
                cs.currentWalletIdx,
                cs.btcTxId,
                cs.btcVout,
                cs.scriptAsm,
                cs.amount,
                cs.claimAddress
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else if (ev.height <= cs.currentTolpBlockHeight) {
          import cats.implicits._
          import xyz.stratalab.sdk.syntax._
          import org.bitcoins.core.currency.Satoshis
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          (cs.startBTCBlockHeight, cs.depositBTCBlockHeight)
            .mapN((startBTCBlockHeight, depositBTCBlockHeight) =>
              FSMTransitionTo(
                currentState,
                MConfirmingTBTCMint(
                  startBTCBlockHeight,
                  depositBTCBlockHeight,
                  cs.currentWalletIdx,
                  cs.scriptAsm,
                  cs.redeemAddress,
                  cs.claimAddress,
                  cs.btcTxId,
                  cs.btcVout,
                  cs.utxoTxId,
                  cs.utxoIndex,
                  Satoshis(int128AsBigInt(cs.amount.amount))
                ),
                t2E(currentState, blockchainEvent)
              )
            )
            .orElse(
              Some(
                FSMTransitionTo(
                  currentState,
                  MWaitingForRedemption(
                    cs.currentTolpBlockHeight,
                    cs.currentWalletIdx,
                    cs.scriptAsm,
                    cs.redeemAddress,
                    cs.claimAddress,
                    cs.btcTxId,
                    cs.btcVout,
                    cs.utxoTxId,
                    cs.utxoIndex,
                    cs.amount
                  ),
                  t2E(currentState, blockchainEvent)
                )
              )
            )
        } else
          None
      case (
            cs: MConfirmingTBTCMint,
            be: NodeFundsWithdrawn
          ) =>
        if (cs.utxoTxId == be.txId && cs.utxoIndex == be.txIndex) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingRedemption(
                Some(cs.startBTCBlockHeight),
                Some(cs.depositTBTCBlockHeight),
                be.secret,
                be.fundsWithdrawnHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.utxoTxId,
                cs.utxoIndex,
                be.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            _: MMintingTBTC,
            _
          ) =>
        None // No transition
      case (
            _: MConfirmingTBTCMint,
            _
          ) =>
        None // No transition
      case (
            _: MWaitingForRedemption,
            _
          ) =>
        None // No transition

      case (
            _: MConfirmingRedemption,
            _
          ) =>
        None // No transition

    })
}

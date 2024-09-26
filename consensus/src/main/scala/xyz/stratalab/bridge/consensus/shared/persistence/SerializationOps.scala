package xyz.stratalab.bridge.consensus.shared.persistence

import xyz.stratalab.bridge.consensus.shared.AssetToken
import xyz.stratalab.bridge.consensus.shared.BifrostCurrencyUnit
import xyz.stratalab.bridge.consensus.shared.GroupToken
import xyz.stratalab.bridge.consensus.shared.Lvl
import xyz.stratalab.bridge.consensus.shared.SeriesToken
import xyz.stratalab.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  AssetToken => AssetTokenCurrency
}
import xyz.stratalab.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  GroupToken => GroupTokenCurrency
}
import xyz.stratalab.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  Lvl => LvlCurrency
}
import xyz.stratalab.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  SeriesToken => SeriesTokenCurrency
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BifrostFundsDeposited => BifrostFundsDepositedEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BifrostFundsWithdrawn => BifrostFundsWithdrawnEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BtcFundsDeposited => BtcFundsDepositedEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BtcFundsWithdrawn => BtcFundsWithdrawnEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  NewBTCBlock => NewBTCBlockEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  NewStrataBlock => NewStrataBlockEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  SkippedBTCBlock => SkippedBTCBlockEvent
}
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  SkippedStrataBlock => SkippedStrataBlockEvent
}
import xyz.stratalab.bridge.consensus.protobuf.{AssetToken => AssetTokenPb}
import xyz.stratalab.bridge.consensus.protobuf.{
  BTCFundsDeposited => BTCFundsDepositedPb
}
import xyz.stratalab.bridge.consensus.protobuf.{
  BTCFundsWithdrawn => BTCFundsWithdrawnPb
}
import xyz.stratalab.bridge.consensus.protobuf.{
  BifrostCurrencyUnit => BifrostCurrencyUnitPb
}
import xyz.stratalab.bridge.consensus.protobuf.{
  BifrostFundsDeposited => BifrostFundsDepositedPb
}
import xyz.stratalab.bridge.consensus.protobuf.{
  BifrostFundsWithdrawn => BifrostFundsWithdrawnPb
}
import xyz.stratalab.bridge.consensus.protobuf.{BlockchainEvent => BlockchainEventPb}
import xyz.stratalab.bridge.consensus.protobuf.{GroupToken => GroupTokenPb}
import xyz.stratalab.bridge.consensus.protobuf.{Lvl => LvlPb}
import xyz.stratalab.bridge.consensus.protobuf.{NewBTCBlock => NewBTCBlockPb}
import xyz.stratalab.bridge.consensus.protobuf.{NewStrataBlock => NewStrataBlockPb}
import xyz.stratalab.bridge.consensus.protobuf.{SeriesToken => SeriesTokenPb}
import xyz.stratalab.bridge.consensus.protobuf.{SkippedBTCBlock => SkippedBTCBlockPb}
import xyz.stratalab.bridge.consensus.protobuf.{
  SkippedStrataBlock => SkippedStrataBlockPb
}
import com.google.protobuf.ByteString
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  BifrostFundsDeposited,
  BlockchainEvent,
  BTCFundsWithdrawn,
  BTCFundsDeposited,
  BifrostFundsWithdrawn,
  SkippedStrataBlock,
  SkippedBTCBlock,
  NewStrataBlock,
  NewBTCBlock
}

trait SerializationOps {

  def toProtobuf(amount: BifrostCurrencyUnit) = amount match {
    case Lvl(amount) =>
      Some(
        BifrostCurrencyUnitPb(
          LvlCurrency(
            LvlPb(amount.value)
          )
        )
      )
    case SeriesToken(id, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          SeriesTokenCurrency(
            SeriesTokenPb(id, amount.value)
          )
        )
      )
    case GroupToken(id, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          GroupTokenCurrency(
            GroupTokenPb(id, amount.value)
          )
        )
      )
    case AssetToken(groupId, seriesId, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          AssetTokenCurrency(
            AssetTokenPb(groupId, seriesId, amount.value)
          )
        )
      )
  }

  def toProtobuf(event: BlockchainEvent): BlockchainEventPb =
    event match {
      case NewBTCBlock(height) =>
        BlockchainEventPb(
          NewBTCBlockEvent(NewBTCBlockPb(height))
        )
      case BTCFundsWithdrawn(txId, vout) =>
        BlockchainEventPb(
          BtcFundsWithdrawnEvent(BTCFundsWithdrawnPb(txId, vout))
        )
      case SkippedBTCBlock(height) =>
        BlockchainEventPb(
          SkippedBTCBlockEvent(SkippedBTCBlockPb(height))
        )
      case SkippedStrataBlock(height) =>
        BlockchainEventPb(
          SkippedStrataBlockEvent(SkippedStrataBlockPb(height))
        )
      case NewStrataBlock(height) =>
        BlockchainEventPb(
          NewStrataBlockEvent(NewStrataBlockPb(height))
        )
      case BTCFundsDeposited(
            fundsDepositedHeight,
            scriptPubKey,
            txId,
            vout,
            amount
          ) =>
        BlockchainEventPb(
          BtcFundsDepositedEvent(
            BTCFundsDepositedPb(
              fundsDepositedHeight,
              scriptPubKey,
              txId,
              vout,
              ByteString.copyFrom(amount.satoshis.bytes.toArray)
            )
          )
        )
      case BifrostFundsDeposited(
            currentStrataBlockHeight,
            address,
            utxoTxId,
            utxoIndex,
            amount
          ) =>
        BlockchainEventPb(
          BifrostFundsDepositedEvent(
            BifrostFundsDepositedPb(
              currentStrataBlockHeight,
              address,
              utxoTxId,
              utxoIndex,
              toProtobuf(amount)
            )
          )
        )
      case BifrostFundsWithdrawn(height, txId, txIndex, secret, amount) =>
        BlockchainEventPb(
          BifrostFundsWithdrawnEvent(
            BifrostFundsWithdrawnPb(
              height,
              txId,
              txIndex,
              secret,
              toProtobuf(amount)
            )
          )
        )
    }
}

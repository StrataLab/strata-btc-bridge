package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.core.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CurrentToplHeightRef
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.Fellowship
import co.topl.bridge.consensus.core.LastReplyMap
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.Template
import co.topl.bridge.consensus.core.pbft.activities.CommitActivity
import co.topl.bridge.consensus.core.pbft.activities.PrePrepareActivity
import co.topl.bridge.consensus.core.pbft.activities.PrepareActivity
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger

import java.security.PublicKey

trait PBFTRequestPreProcessor[F[_]] {

  def preProcessRequest(request: PrePrepareRequest): F[Unit]
  def preProcessRequest(request: PrepareRequest): F[Unit]
  def preProcessRequest(request: CommitRequest): F[Unit]

}

object PBFTRequestPreProcessorImpl {

  def make[F[_]: Async: Logger](
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      requestStateManager: RequestStateManager[F],
      currentViewRef: CurrentViewRef[F],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount,
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeightRef[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel],
      defaultMintingFee: Lvl,
      lastReplyMap: LastReplyMap,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      defaultFeePerByte: CurrencyUnit
  ): PBFTRequestPreProcessor[F] = new PBFTRequestPreProcessor[F] {

    override def preProcessRequest(request: PrePrepareRequest): F[Unit] = {
      PrePrepareActivity(
        request
      )(replicaKeysMap)
    }

    override def preProcessRequest(request: PrepareRequest): F[Unit] =
      PrepareActivity(
        request
      )(replicaKeysMap)
    override def preProcessRequest(request: CommitRequest): F[Unit] =
      CommitActivity(
        request
      )(replicaKeysMap)

  }
}

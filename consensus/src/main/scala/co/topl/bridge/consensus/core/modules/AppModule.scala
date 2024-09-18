package co.topl.bridge.consensus.core.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.servicekit.FellowshipStorageApi
import co.topl.brambl.servicekit.TemplateStorageApi
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CheckpointInterval
import co.topl.bridge.consensus.core.CurrentBTCHeightRef
import co.topl.bridge.consensus.core.CurrentToplHeightRef
import co.topl.bridge.consensus.core.Fellowship
import co.topl.bridge.consensus.core.KWatermark
import co.topl.bridge.consensus.core.LastReplyMap
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.SystemGlobalState
import co.topl.bridge.consensus.core.Template
import co.topl.bridge.consensus.core.ToplBTCBridgeConsensusParamConfig
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.core.channelResource
import co.topl.bridge.consensus.core.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.core.managers.WalletManagementUtils
import co.topl.bridge.consensus.core.pbft.CheckpointManagerImpl
import co.topl.bridge.consensus.core.pbft.PBFTInternalEvent
import co.topl.bridge.consensus.core.pbft.PBFTRequestPreProcessorImpl
import co.topl.bridge.consensus.core.pbft.RequestStateManagerImpl
import co.topl.bridge.consensus.core.pbft.RequestTimerManagerImpl
import co.topl.bridge.consensus.core.pbft.statemachine.BridgeStateMachineExecutionManagerImpl
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.consensus.shared.BTCConfirmationThreshold
import co.topl.bridge.consensus.shared.BTCRetryThreshold
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.shared.ToplConfirmationThreshold
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.subsystems.monitor.MonitorStateMachine
import co.topl.bridge.consensus.subsystems.monitor.SessionEvent
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerImpl
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.StateMachineServiceGrpcClient
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import io.grpc.Metadata
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.security.PublicKey
import java.security.{KeyPair => JKeyPair}
import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.consensus.core.pbft.ViewManagerImpl

trait AppModule extends WalletStateResource {

  def webUI() = HttpRoutes.of[IO] { case request @ GET -> Root =>
    StaticFile
      .fromResource("/static/index.html", Some(request))
      .getOrElseF(InternalServerError())
  }

  def createApp(
      replicaKeysMap: Map[Int, PublicKey],
      replicaKeyPair: JKeyPair,
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      params: ToplBTCBridgeConsensusParamConfig,
      queue: Queue[IO, SessionEvent],
      walletManager: BTCWalletAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      logger: Logger[IO],
      currentBitcoinNetworkHeight: Ref[IO, Int],
      currentSequenceRef: Ref[IO, Long],
      currentToplHeight: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      pbftProtocolClient: PBFTInternalGrpcServiceClient[IO],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      clientId: ClientId,
      storageApi: StorageApi[IO],
      consensusClient: StateMachineServiceGrpcClient[IO],
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
      fromFellowship: Fellowship,
      fromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      btcRetryThreshold: BTCRetryThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
  ) = {
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletRes = walletResource(params.toplWalletDb)
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletRes, walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      params.toplNetwork.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )
    implicit val genusQueryAlgebra = GenusQueryAlgebra.make[IO](
      channelResource(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection
      )
    )
    implicit val fellowshipStorageApi = FellowshipStorageApi.make(walletRes)
    implicit val templateStorageApi = TemplateStorageApi.make(walletRes)
    implicit val sessionManagerPermanent =
      SessionManagerImpl.makePermanent[IO](storageApi, queue)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    import co.topl.brambl.syntax._
    implicit val defaultMintingFee = Lvl(params.mintingFee)
    implicit val asyncForIO = IO.asyncForIO
    implicit val l = logger
    implicit val btcWaitExpirationTime = new BTCWaitExpirationTime(
      params.btcWaitExpirationTime
    )
    implicit val toplWaitExpirationTime = new ToplWaitExpirationTime(
      params.toplWaitExpirationTime
    )
    implicit val btcConfirmationThreshold = new BTCConfirmationThreshold(
      params.btcConfirmationThreshold
    )
    implicit val toplConfirmationThreshold = new ToplConfirmationThreshold(
      params.toplConfirmationThreshold
    )
    implicit val checkpointInterval = new CheckpointInterval(
      params.checkpointInterval
    )
    implicit val lastReplyMap = new LastReplyMap(
      new ConcurrentHashMap[(ClientId, Long), Result]()
    )
    implicit val defaultFeePerByte = params.feePerByte
    implicit val iPeginWalletManager = new PeginWalletManager(
      pegInWalletManager
    )
    implicit val iBridgeWalletManager = new BridgeWalletManager(walletManager)
    implicit val btcNetwork = params.btcNetwork
    implicit val toplChannelResource = channelResource(
      params.toplHost,
      params.toplPort,
      params.toplSecureConnection
    )
    implicit val currentBTCHeightRef =
      new CurrentBTCHeightRef[IO](currentBitcoinNetworkHeight)
    implicit val currentToplHeightRef = new CurrentToplHeightRef[IO](
      currentToplHeight
    )
    implicit val watermarkRef = new WatermarkRef[IO](
      Ref.unsafe[IO, (Long, Long)]((0, 0))
    )
    implicit val kWatermark = new KWatermark(params.kWatermark)
    import scala.concurrent.duration._
    for {
      queue <- Queue.unbounded[IO, PBFTInternalEvent]
      checkpointManager <- CheckpointManagerImpl.make[IO]()
      viewManager <- ViewManagerImpl.make[IO]()
      bridgeStateMachineExecutionManager <-
        BridgeStateMachineExecutionManagerImpl
          .make[IO](
            replicaKeyPair,
            viewManager,
            walletManagementUtils,
            params.toplWalletSeedFile,
            params.toplWalletPassword
          )
      requestTimerManager <- RequestTimerManagerImpl.make[IO](
        params.requestTimeout.seconds,
        queue
      )
      requestStateManager <- RequestStateManagerImpl
        .make[IO](
          replicaKeyPair,
          queue,
          requestTimerManager,
          bridgeStateMachineExecutionManager
        )

    } yield {
      implicit val iRequestStateManager = requestStateManager
      implicit val iRequestTimerManager = requestTimerManager
      implicit val iViewManager = viewManager
      implicit val iCheckpointManager = checkpointManager
      implicit val pbftReqProcessor = PBFTRequestPreProcessorImpl.make[IO](
        queue,
        viewManager,
        replicaKeysMap
      )
      val peginStateMachine = MonitorStateMachine
        .make[IO](
          currentBitcoinNetworkHeight,
          currentToplHeight,
          new ConcurrentHashMap()
        )
      (
        co.topl.bridge.consensus.core.StateMachineGrpcServiceServer
          .stateMachineGrpcServiceServer(
            replicaKeyPair,
            pbftProtocolClient,
            idReplicaClientMap,
            currentSequenceRef
          ),
        InitializationModule
          .make[IO](currentBitcoinNetworkHeight, currentState),
        peginStateMachine,
        co.topl.bridge.consensus.core.pbft.PBFTInternalGrpcServiceServer
          .pbftInternalGrpcServiceServer(
            replicaKeysMap
          ),
        requestStateManager
      )
    }
  }
}

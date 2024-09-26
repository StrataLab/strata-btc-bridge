package xyz.stratalab.bridge.consensus.core.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.models.{GroupId, SeriesId}
import co.topl.brambl.servicekit.{
  FellowshipStorageApi,
  TemplateStorageApi,
  WalletKeyApi,
  WalletStateApi,
  WalletStateResource
}
import co.topl.brambl.wallet.WalletApi
import io.grpc.Metadata
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, _}
import org.typelevel.log4cats.Logger
import xyz.stratalab.bridge.consensus.core.managers.{BTCWalletAlgebra, WalletManagementUtils}
import xyz.stratalab.bridge.consensus.core.pbft.statemachine.BridgeStateMachineExecutionManagerImpl
import xyz.stratalab.bridge.consensus.core.pbft.{
  CheckpointManagerImpl,
  PBFTInternalEvent,
  PBFTRequestPreProcessorImpl,
  RequestStateManagerImpl,
  RequestTimerManagerImpl,
  ViewManagerImpl
}
import xyz.stratalab.bridge.consensus.core.{
  BridgeWalletManager,
  CheckpointInterval,
  CurrentBTCHeightRef,
  CurrentStrataHeightRef,
  Fellowship,
  KWatermark,
  LastReplyMap,
  PeginWalletManager,
  PublicApiClientGrpcMap,
  StrataBTCBridgeConsensusParamConfig,
  SystemGlobalState,
  Template,
  WatermarkRef,
  channelResource
}
import xyz.stratalab.bridge.consensus.service.StateMachineReply.Result
import xyz.stratalab.bridge.consensus.service.StateMachineServiceFs2Grpc
import xyz.stratalab.bridge.consensus.shared.persistence.StorageApi
import xyz.stratalab.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  BTCWaitExpirationTime,
  Lvl,
  StrataConfirmationThreshold,
  StrataWaitExpirationTime
}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{MonitorStateMachine, SessionEvent, SessionManagerImpl}
import xyz.stratalab.bridge.shared.{ClientId, ReplicaCount, ReplicaId, StateMachineServiceGrpcClient}
import xyz.stratalab.consensus.core.PBFTInternalGrpcServiceClient

import java.security.{KeyPair => JKeyPair, PublicKey}
import java.util.concurrent.ConcurrentHashMap

trait AppModule extends WalletStateResource {

  def webUI() = HttpRoutes.of[IO] { case request @ GET -> Root =>
    StaticFile
      .fromResource("/static/index.html", Some(request))
      .getOrElseF(InternalServerError())
  }

  def createApp(
    replicaKeysMap:              Map[Int, PublicKey],
    replicaKeyPair:              JKeyPair,
    idReplicaClientMap:          Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
    params:                      StrataBTCBridgeConsensusParamConfig,
    queue:                       Queue[IO, SessionEvent],
    walletManager:               BTCWalletAlgebra[IO],
    pegInWalletManager:          BTCWalletAlgebra[IO],
    logger:                      Logger[IO],
    currentBitcoinNetworkHeight: Ref[IO, Int],
    currentSequenceRef:          Ref[IO, Long],
    currentStrataHeight:         Ref[IO, Long],
    currentState:                Ref[IO, SystemGlobalState]
  )(implicit
    pbftProtocolClient:     PBFTInternalGrpcServiceClient[IO],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
    clientId:               ClientId,
    storageApi:             StorageApi[IO],
    consensusClient:        StateMachineServiceGrpcClient[IO],
    replicaId:              ReplicaId,
    replicaCount:           ReplicaCount,
    fromFellowship:         Fellowship,
    fromTemplate:           Template,
    bitcoindInstance:       BitcoindRpcClient,
    btcRetryThreshold:      BTCRetryThreshold,
    groupIdIdentifier:      GroupId,
    seriesIdIdentifier:     SeriesId
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
    implicit val toplWaitExpirationTime = new StrataWaitExpirationTime(
      params.toplWaitExpirationTime
    )
    implicit val btcConfirmationThreshold = new BTCConfirmationThreshold(
      params.btcConfirmationThreshold
    )
    implicit val toplConfirmationThreshold = new StrataConfirmationThreshold(
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
    implicit val currentStrataHeightRef = new CurrentStrataHeightRef[IO](
      currentStrataHeight
    )
    implicit val watermarkRef = new WatermarkRef[IO](
      Ref.unsafe[IO, (Long, Long)]((0, 0))
    )
    implicit val kWatermark = new KWatermark(params.kWatermark)
    import scala.concurrent.duration._
    for {
      queue             <- Queue.unbounded[IO, PBFTInternalEvent]
      checkpointManager <- CheckpointManagerImpl.make[IO]()
      requestTimerManager <- RequestTimerManagerImpl.make[IO](
        params.requestTimeout.seconds,
        queue
      )
      viewManager <- ViewManagerImpl.make[IO](
        params.viewChangeTimeout,
        storageApi,
        checkpointManager,
        requestTimerManager
      )
      bridgeStateMachineExecutionManager <-
        BridgeStateMachineExecutionManagerImpl
          .make[IO](
            replicaKeyPair,
            viewManager,
            walletManagementUtils,
            params.toplWalletSeedFile,
            params.toplWalletPassword
          )
      requestStateManager <- RequestStateManagerImpl
        .make[IO](
          viewManager,
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
          currentStrataHeight,
          new ConcurrentHashMap()
        )
      (
        xyz.stratalab.bridge.consensus.core.StateMachineGrpcServiceServer
          .stateMachineGrpcServiceServer(
            replicaKeyPair,
            pbftProtocolClient,
            idReplicaClientMap,
            currentSequenceRef
          ),
        InitializationModule
          .make[IO](currentBitcoinNetworkHeight, currentState),
        peginStateMachine,
        xyz.stratalab.bridge.consensus.core.pbft.PBFTInternalGrpcServiceServer
          .pbftInternalGrpcServiceServer(
            replicaKeysMap
          ),
        requestStateManager
      )
    }
  }
}

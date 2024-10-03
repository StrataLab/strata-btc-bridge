package xyz.stratalab.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Ref, Resource, Sync}
import cats.effect.std.Queue
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.{FellowshipStorageAlgebra, GenusQueryAlgebra, TemplateStorageAlgebra, WalletStateAlgebra}
import co.topl.brambl.models.{GroupId, SeriesId}
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector
import xyz.stratalab.bridge.consensus.core.controllers.StartSessionController
import xyz.stratalab.bridge.consensus.core.managers.WalletManagementUtils
import xyz.stratalab.bridge.consensus.core.pbft.ViewManager
import xyz.stratalab.bridge.consensus.core.pbft.statemachine.PBFTEvent
import xyz.stratalab.bridge.consensus.core.{
  stateDigest,
  BitcoinNetworkIdentifiers,
  BridgeWalletManager,
  CheckpointInterval,
  CurrentBTCHeightRef,
  CurrentStrataHeightRef,
  Fellowship,
  LastReplyMap,
  PeginWalletManager,
  PublicApiClientGrpcMap,
  StrataKeypair,
  Template
}
import xyz.stratalab.bridge.consensus.pbft.CheckpointRequest
import xyz.stratalab.bridge.consensus.service.StateMachineReply.Result
import xyz.stratalab.bridge.consensus.service.{InvalidInputRes, StartSessionRes}
import xyz.stratalab.bridge.consensus.shared.PeginSessionState.{
  PeginSessionStateMintingTBTC,
  PeginSessionStateSuccessfulPegin,
  PeginSessionStateTimeout,
  PeginSessionStateWaitingForBTC,
  PeginSessionWaitingForClaim
}
import xyz.stratalab.bridge.consensus.shared.{
  AssetToken,
  BTCWaitExpirationTime,
  Lvl,
  MiscUtils,
  PeginSessionState,
  StrataWaitExpirationTime
}
import xyz.stratalab.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import xyz.stratalab.bridge.shared.StateMachineRequest.Operation.{
  PostClaimTx,
  PostDepositBTC,
  PostRedemptionTx,
  StartSession,
  TimeoutDepositBTC,
  TimeoutTBTCMint
}
import xyz.stratalab.bridge.shared.{
  BridgeCryptoUtils,
  BridgeError,
  ClientId,
  ReplicaCount,
  ReplicaId,
  StartSessionOperation,
  StateMachineRequest
}
import xyz.stratalab.consensus.core.PBFTInternalGrpcServiceClient

import java.security.{KeyPair => JKeyPair}
import java.util.UUID

trait BridgeStateMachineExecutionManager[F[_]] {

  def executeRequest(
    sequenceNumber: Long,
    request:        xyz.stratalab.bridge.shared.StateMachineRequest
  ): F[Unit]

  def runStream(): fs2.Stream[F, Unit]

}

object BridgeStateMachineExecutionManagerImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._
  import WaitingForRedemptionOps._

  def make[F[_]: Async: Logger](
    keyPair:               JKeyPair,
    viewManager:           ViewManager[F],
    walletManagementUtils: WalletManagementUtils[F],
    toplWalletSeedFile:    String,
    toplWalletPassword:    String
  )(implicit
    pbftProtocolClientGrpc:   PBFTInternalGrpcServiceClient[F],
    replica:                  ReplicaId,
    publicApiClientGrpcMap:   PublicApiClientGrpcMap[F],
    checkpointInterval:       CheckpointInterval,
    sessionManager:           SessionManagerAlgebra[F],
    currentBTCHeightRef:      CurrentBTCHeightRef[F],
    btcNetwork:               BitcoinNetworkIdentifiers,
    pegInWalletManager:       PeginWalletManager[F],
    bridgeWalletManager:      BridgeWalletManager[F],
    fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
    templateStorageAlgebra:   TemplateStorageAlgebra[F],
    toplWaitExpirationTime:   StrataWaitExpirationTime,
    btcWaitExpirationTime:    BTCWaitExpirationTime,
    tba:                      TransactionBuilderApi[F],
    currentStrataHeight:      CurrentStrataHeightRef[F],
    walletApi:                WalletApi[F],
    wsa:                      WalletStateAlgebra[F],
    groupIdIdentifier:        GroupId,
    seriesIdIdentifier:       SeriesId,
    utxoAlgebra:              GenusQueryAlgebra[F],
    channelResource:          Resource[F, ManagedChannel],
    defaultMintingFee:        Lvl,
    lastReplyMap:             LastReplyMap,
    defaultFromFellowship:    Fellowship,
    defaultFromTemplate:      Template,
    bitcoindInstance:         BitcoindRpcClient,
    replicaCount:             ReplicaCount,
    defaultFeePerByte:        CurrencyUnit
  ) = {
    for {
      tKeyPair <- walletManagementUtils.loadKeys(
        toplWalletSeedFile,
        toplWalletPassword
      )
      state              <- Ref.of[F, Map[String, PBFTState]](Map.empty)
      queue              <- Queue.unbounded[F, (Long, StateMachineRequest)]
      elegibilityManager <- ExecutionElegibilityManagerImpl.make[F]()
    } yield {
      implicit val toplKeypair = new StrataKeypair(tKeyPair)
      new BridgeStateMachineExecutionManager[F] {

        def runStream(): fs2.Stream[F, Unit] =
          fs2.Stream
            .fromQueueUnterminated[F, (Long, StateMachineRequest)](queue)
            .evalMap(x =>
              trace"Appending request: ${x._1}, ${x._2}" >>
              elegibilityManager.appendOrUpdateRequest(
                x._1,
                x._2
              )
            )
            .flatMap(_ =>
              fs2.Stream
                .unfoldEval[F, Unit, (Long, StateMachineRequest)](())(_ =>
                  elegibilityManager.getNextExecutableRequest().map(_.map(x => ((x._1, x._2), ())))
                )
            )
            .evalMap(x => trace"Executing the request: ${x._2}" >> executeRequestF(x._1, x._2))

        private def startSession(
          clientNumber: Int,
          timestamp:    Long,
          sc:           StartSessionOperation
        ): F[Result] = {
          import StartSessionController._
          for {
            _ <-
              info"Received start session request from client ${clientNumber}"
            sessionId <- Sync[F].delay(
              sc.sessionId.getOrElse(UUID.randomUUID().toString)
            )
            _ <- debug"Session ID: $sessionId"
            res <- startPeginSession[F](
              sessionId,
              sc
            )
            viewNumber       <- viewManager.currentView
            currentBTCHeight <- currentBTCHeightRef.underlying.get
            resp <- res match {
              case Left(e: BridgeError) =>
                Sync[F].delay(
                  Result.InvalidInput(
                    InvalidInputRes(
                      e.error
                    )
                  )
                )
              case Right((sessionInfo, response)) =>
                state.update(
                  _.+(
                    sessionId ->
                    PSWaitingForBTCDeposit(
                      height = currentBTCHeight,
                      currentWalletIdx = sessionInfo.btcPeginCurrentWalletIdx,
                      scriptAsm = sessionInfo.scriptAsm,
                      escrowAddress = sessionInfo.escrowAddress,
                      redeemAddress = sessionInfo.redeemAddress,
                      claimAddress = sessionInfo.claimAddress
                    )
                  )
                ) >> sessionManager
                  .createNewSession(sessionId, sessionInfo) >> Sync[F].delay(
                  Result.StartSession(
                    StartSessionRes(
                      sessionId = response.sessionID,
                      script = response.script,
                      escrowAddress = response.escrowAddress,
                      descriptor = response.descriptor,
                      minHeight = response.minHeight,
                      maxHeight = response.maxHeight
                    )
                  )
                )
            }
            _ <- publicApiClientGrpcMap
              .underlying(ClientId(clientNumber))
              ._1
              .replyStartPegin(timestamp, viewNumber, resp)
          } yield resp
        }

        private def toEvt(op: StateMachineRequest.Operation)(implicit
          groupIdIdentifier:  GroupId,
          seriesIdIdentifier: SeriesId
        ): PBFTEvent =
          op match {
            case StateMachineRequest.Operation.Empty =>
              throw new Exception("Invalid operation")
            case StartSession(_) =>
              throw new Exception("Invalid operation")
            case TimeoutTBTCMint(value) =>
              TimeoutMinting(value.sessionId)
            case TimeoutDepositBTC(value) =>
              TimeoutDeposit(value.sessionId)
            case PostDepositBTC(value) =>
              PostDepositBTCEvt(
                sessionId = value.sessionId,
                height = value.height,
                txId = value.txId,
                vout = value.vout,
                amount = Satoshis.fromBytes(ByteVector(value.amount.toByteArray))
              )
            case PostRedemptionTx(value) =>
              import co.topl.brambl.syntax._
              PostRedemptionTxEvt(
                sessionId = value.sessionId,
                secret = value.secret,
                height = value.height,
                utxoTxId = value.utxoTxId,
                utxoIdx = value.utxoIndex,
                amount = AssetToken(
                  Encoding.encodeToBase58(groupIdIdentifier.value.toByteArray),
                  Encoding.encodeToBase58(seriesIdIdentifier.value.toByteArray),
                  BigInt(value.amount.toByteArray())
                )
              )
            case PostClaimTx(value) =>
              PostClaimTxEvt(
                sessionId = value.sessionId,
                height = value.height,
                txId = value.txId,
                vout = value.vout
              )
          }

        private def executeStateMachine(
          sessionId: String,
          pbftEvent: PBFTEvent
        ): F[Option[PBFTState]] =
          for {
            currentState <- state.get.map(_.apply(sessionId))
            _            <- debug"Current state: $currentState"
            newState = PBFTTransitionRelation
              .handlePBFTEvent(
                currentState,
                pbftEvent
              )
            _ <- debug"New state: $newState"
            _ <- state.update(x =>
              newState
                .map(y =>
                  x.updated(
                    sessionId,
                    y
                  )
                )
                .getOrElse(x)
            )
          } yield newState

        private def pbftStateToPeginSessionState(
          pbftState: PBFTState
        ): PeginSessionState =
          pbftState match {
            case _: PSWaitingForBTCDeposit => PeginSessionStateWaitingForBTC
            case _: PSMintingTBTC          => PeginSessionStateMintingTBTC
            case _: PSClaimingBTC          => PeginSessionWaitingForClaim
          }

        private def standardResponse(
          sessionId: String,
          value:     StateMachineRequest.Operation
        ) =
          for {
            newState <- executeStateMachine(
              sessionId,
              toEvt(value)
            )
            someSessionInfo <- sessionManager.updateSession(
              sessionId,
              x =>
                newState
                  .map(y =>
                    x.copy(
                      mintingBTCState = pbftStateToPeginSessionState(y)
                    )
                  )
                  .getOrElse(x)
            )
          } yield someSessionInfo

        private def executeRequestAux(
          request: xyz.stratalab.bridge.shared.StateMachineRequest
        ) =
          (request.operation match {
            case StateMachineRequest.Operation.Empty =>
              // This is just a no-op for the when an operation
              // is used to vote on the result but requires no
              // action
              trace"No op" >> Sync[F].delay(Result.Empty)
            case StartSession(sc) =>
              trace"handling StartSession" >> startSession(
                request.clientNumber,
                request.timestamp,
                sc
              )
            case PostDepositBTC(
                  value
                ) =>
              import WaitingBTCOps._
              import co.topl.brambl.syntax._
              for {
                _ <- debug"handling PostDepositBTC ${value.sessionId}"
                someSessionInfo <- standardResponse(
                  value.sessionId,
                  request.operation
                )
                currentPrimary <- viewManager.currentPrimary
                _ <- someSessionInfo
                  .flatMap(sessionInfo =>
                    if (currentPrimary == replica.id)
                      MiscUtils.sessionInfoPeginPrism
                        .getOption(sessionInfo)
                        .map(peginSessionInfo =>
                          debug"Starting minting process" >>
                          startMintingProcess[F](
                            defaultFromFellowship,
                            defaultFromTemplate,
                            peginSessionInfo.redeemAddress,
                            BigInt(value.amount.toByteArray())
                          )
                        )
                    else None
                  )
                  .getOrElse(Sync[F].unit)
              } yield Result.Empty
            case TimeoutDepositBTC(value) =>
              trace"handling TimeoutDepositBTC ${value.sessionId}" >> state.update(_ - (value.sessionId)) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> Result.Empty.pure[F]
            case TimeoutTBTCMint(
                  value
                ) =>
              trace"handling TimeoutTBTCMint ${value.sessionId}" >> state.update(_ - value.sessionId) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> Result.Empty.pure[F]
            case PostRedemptionTx(
                  value
                ) =>
              for {
                _ <- trace"handling PostRedemptionTx ${value.sessionId}"
                someSessionInfo <- standardResponse(
                  value.sessionId,
                  request.operation
                )
                _ <- (for {
                  sessionInfo <- someSessionInfo
                  peginSessionInfo <- MiscUtils.sessionInfoPeginPrism
                    .getOption(sessionInfo)
                } yield startClaimingProcess[F](
                  value.secret,
                  peginSessionInfo.claimAddress,
                  peginSessionInfo.btcBridgeCurrentWalletIdx,
                  value.txId,
                  value.vout,
                  peginSessionInfo.scriptAsm, // scriptAsm,
                  Satoshis
                    .fromLong(
                      BigInt(value.amount.toByteArray()).toLong
                    )
                )).getOrElse(Sync[F].unit)
              } yield Result.Empty
            case PostClaimTx(value) =>
              debug"handling PostClaimTx ${value.sessionId}" >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateSuccessfulPegin
              ) >> Result.Empty.pure[F]
          }).flatMap(x =>
            Sync[F].delay(
              lastReplyMap.underlying.put(
                (ClientId(request.clientNumber), request.timestamp),
                x
              )
            )
          )

        private def executeRequestF(
          sequenceNumber: Long,
          request:        xyz.stratalab.bridge.shared.StateMachineRequest
        ) = {
          import xyz.stratalab.bridge.shared.implicits._
          import cats.implicits._
          for {
            _ <- executeRequestAux(request)
            // here we start the checkpoint
            _ <-
              if (sequenceNumber % checkpointInterval.underlying == 0)
                for {
                  digest <- state.get.map(stateDigest)
                  checkpointRequest <- CheckpointRequest(
                    sequenceNumber = sequenceNumber,
                    digest = ByteString.copyFrom(digest),
                    replicaId = replica.id
                  ).pure[F]
                  signedBytes <- BridgeCryptoUtils.signBytes[F](
                    keyPair.getPrivate(),
                    checkpointRequest.signableBytes
                  )
                  _ <- pbftProtocolClientGrpc.checkpoint(
                    checkpointRequest.withSignature(
                      ByteString.copyFrom(signedBytes)
                    )
                  )
                } yield ()
              else Async[F].unit
          } yield ()
        }

        def executeRequest(
          sequenceNumber: Long,
          request:        xyz.stratalab.bridge.shared.StateMachineRequest
        ): F[Unit] =
          for {
            _           <- queue.offer((sequenceNumber, request))
            currentView <- viewManager.currentView
            _ <- request.operation match {
              case StateMachineRequest.Operation.StartSession(_) =>
                Sync[F].unit
              case _ =>
                publicApiClientGrpcMap
                  .underlying(ClientId(request.clientNumber))
                  ._1
                  .replyStartPegin(request.timestamp, currentView, Result.Empty)
            }
          } yield ()
      }
    }
  }

}

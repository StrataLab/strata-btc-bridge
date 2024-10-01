package xyz.stratalab.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Ref, Resource, Sync}
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
  ReplicaId,
  StartSessionOperation,
  StateMachineRequest
}
import xyz.stratalab.consensus.core.PBFTInternalGrpcServiceClient

import java.security.{KeyPair => JKeyPair}
import java.util.UUID

trait BridgeStateMachineExecutionManager[F[_]] {

  def executeRequest(
    request: xyz.stratalab.bridge.shared.StateMachineRequest
  ): F[Unit]

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
    defaultFeePerByte:        CurrencyUnit
  ) = {
    for {
      tKeyPair <- walletManagementUtils.loadKeys(
        toplWalletSeedFile,
        toplWalletPassword
      )
      state <- Ref.of[F, Map[String, PBFTState]](Map.empty)
    } yield {
      implicit val iViewManager = viewManager
      implicit val toplKeypair = new StrataKeypair(tKeyPair)
      new BridgeStateMachineExecutionManager[F] {

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
            case TimeoutDepositBTC(_) =>
              throw new Exception("Invalid operation")
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
            newState = PBFTTransitionRelation
              .handlePBFTEvent(
                currentState,
                pbftEvent
              )
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
          clientNumber: Int,
          timestamp:    Long,
          sessionId:    String,
          value:        StateMachineRequest.Operation
        ) =
          for {
            viewNumber <- viewManager.currentView
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
            _ <- publicApiClientGrpcMap
              .underlying(ClientId(clientNumber))
              ._1
              .replyStartPegin(timestamp, viewNumber, Result.Empty)
          } yield someSessionInfo

        private def sendResponse[F[_]: Sync](
          clientNumber: Int,
          timestamp:    Long
        )(implicit
          viewManager:            ViewManager[F],
          publicApiClientGrpcMap: PublicApiClientGrpcMap[F]
        ) =
          for {
            viewNumber <- viewManager.currentView
            _ <- publicApiClientGrpcMap
              .underlying(ClientId(clientNumber))
              ._1
              .replyStartPegin(timestamp, viewNumber, Result.Empty)
          } yield Result.Empty

        private def executeRequestAux(
          request: xyz.stratalab.bridge.shared.StateMachineRequest
        ) =
          (request.operation match {
            case StateMachineRequest.Operation.Empty =>
              warn"Received empty message" >> Sync[F].delay(Result.Empty)
            case StartSession(sc) =>
              trace"handling StartSession" >> startSession(
                request.clientNumber,
                request.timestamp,
                sc
              )
            case PostDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              trace"handling PostDepositBTC ${value.sessionId}" >> standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case TimeoutDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              trace"handling TimeoutDepositBTC ${value.sessionId}" >> state.update(_ - (value.sessionId)) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> sendResponse(
                request.clientNumber,
                request.timestamp
              ) // FIXME: this is just a change of state at db level
            case TimeoutTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              trace"handling TimeoutTBTCMint ${value.sessionId}" >> state.update(_ - value.sessionId) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> sendResponse(
                request.clientNumber,
                request.timestamp
              ) // FIXME: this is just a change of state at db level
            case PostRedemptionTx(
                  value
                ) => // FIXME: Add checks before executing
              for {
                _ <- trace"handling PostRedemptionTx ${value.sessionId}"
                someSessionInfo <- standardResponse(
                  request.clientNumber,
                  request.timestamp,
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
              trace"handling PostClaimTx ${value.sessionId}" >> standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
          }).flatMap(x =>
            Sync[F].delay(
              lastReplyMap.underlying.put(
                (ClientId(request.clientNumber), request.timestamp),
                x
              )
            )
          )

        private def executeRequestF(
          request:                xyz.stratalab.bridge.shared.StateMachineRequest,
          keyPair:                JKeyPair,
          pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
        ) = {
          import xyz.stratalab.bridge.shared.implicits._
          import cats.implicits._
          for {
            currentSequence <- viewManager.currentView
            _               <- executeRequestAux(request)
            // here we start the checkpoint
            _ <-
              if (currentSequence % checkpointInterval.underlying == 0)
                for {
                  digest <- state.get.map(stateDigest)
                  checkpointRequest <- CheckpointRequest(
                    sequenceNumber = currentSequence,
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
          request: xyz.stratalab.bridge.shared.StateMachineRequest
        ): F[Unit] =
          executeRequestF(
            request,
            keyPair,
            pbftProtocolClientGrpc
          )
      }
    }
  }

}

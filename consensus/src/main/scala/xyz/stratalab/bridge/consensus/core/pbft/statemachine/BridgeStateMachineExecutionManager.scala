package xyz.stratalab.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Ref, Resource, Sync}
import xyz.stratalab.sdk.builders.TransactionBuilderApi
import xyz.stratalab.sdk.dataApi.{
  FellowshipStorageAlgebra,
  GenusQueryAlgebra,
  TemplateStorageAlgebra,
  WalletStateAlgebra
}
import xyz.stratalab.sdk.models.{GroupId, SeriesId}
import xyz.stratalab.sdk.utils.Encoding
import xyz.stratalab.sdk.wallet.WalletApi
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector
import xyz.stratalab.bridge.consensus.core.controllers.StartSessionController
import xyz.stratalab.bridge.consensus.core.managers.WalletManagementUtils
import xyz.stratalab.bridge.consensus.core.pbft.ViewManager
import xyz.stratalab.bridge.consensus.core.pbft.statemachine.{
  ConfirmDepositBTCEvt,
  ConfirmTBTCMintEvt,
  PBFTEvent,
  PBFTTransitionRelation,
  PostClaimTxEvt,
  PostDepositBTCEvt,
  PostRedemptionTxEvt,
  PostTBTCMintEvt,
  UndoClaimTxEvt,
  UndoDepositBTCEvt,
  UndoTBTCMintEvt
}
import xyz.stratalab.bridge.consensus.core.{
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
  Template,
  stateDigest
}
import xyz.stratalab.bridge.consensus.pbft.CheckpointRequest
import xyz.stratalab.bridge.consensus.service.StateMachineReply.Result
import xyz.stratalab.bridge.consensus.service.{InvalidInputRes, StartSessionRes}
import xyz.stratalab.bridge.consensus.shared.PeginSessionState.{
  PeginSessionMintingTBTCConfirmation,
  PeginSessionStateMintingTBTC,
  PeginSessionStateSuccessfulPegin,
  PeginSessionStateTimeout,
  PeginSessionStateWaitingForBTC,
  PeginSessionWaitingForClaim,
  PeginSessionWaitingForClaimBTCConfirmation,
  PeginSessionWaitingForEscrowBTCConfirmation,
  PeginSessionWaitingForRedemption
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
  ConfirmClaimTx,
  ConfirmDepositBTC,
  ConfirmTBTCMint,
  PostClaimTx,
  PostDepositBTC,
  PostRedemptionTx,
  PostTBTCMint,
  StartSession,
  TimeoutDepositBTC,
  TimeoutTBTCMint,
  UndoClaimTx,
  UndoDepositBTC,
  UndoTBTCMint
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
  import WaitingBTCOps._
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
            case UndoDepositBTC(value) =>
              UndoDepositBTCEvt(
                sessionId = value.sessionId
              )
            case ConfirmDepositBTC(value) =>
              ConfirmDepositBTCEvt(
                sessionId = value.sessionId,
                height = value.height
              )
            case PostTBTCMint(value) =>
              import xyz.stratalab.sdk.syntax._
              PostTBTCMintEvt(
                sessionId = value.sessionId,
                height = value.height,
                utxoTxId = value.utxoTxId,
                utxoIdx = value.utxoIndex,
                amount = AssetToken(
                  Encoding.encodeToBase58(groupIdIdentifier.value.toByteArray),
                  Encoding.encodeToBase58(seriesIdIdentifier.value.toByteArray),
                  BigInt(value.amount.toByteArray())
                )
              )
            case TimeoutTBTCMint(_) =>
              throw new Exception("Invalid operation")
            case UndoTBTCMint(value) =>
              UndoTBTCMintEvt(
                sessionId = value.sessionId
              )
            case ConfirmTBTCMint(value) =>
              ConfirmTBTCMintEvt(
                sessionId = value.sessionId,
                height = value.height
              )
            case PostRedemptionTx(value) =>
              import xyz.stratalab.sdk.syntax._
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
            case UndoClaimTx(value) =>
              UndoClaimTxEvt(
                sessionId = value.sessionId
              )
            case ConfirmClaimTx(_) =>
              throw new Exception("Invalid operation")
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
            case _: PSConfirmingBTCDeposit =>
              PeginSessionWaitingForEscrowBTCConfirmation
            case _: PSMintingTBTC          => PeginSessionStateMintingTBTC
            case _: PSWaitingForRedemption => PeginSessionWaitingForRedemption
            case _: PSConfirmingTBTCMint   => PeginSessionMintingTBTCConfirmation
            case _: PSClaimingBTC          => PeginSessionWaitingForClaim
            case _: PSConfirmingBTCClaim =>
              PeginSessionWaitingForClaimBTCConfirmation
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
              startSession(
                request.clientNumber,
                request.timestamp,
                sc
              )
            case PostDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case TimeoutDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              state.update(_ - (value.sessionId)) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> sendResponse(
                request.clientNumber,
                request.timestamp
              ) // FIXME: this is just a change of state at db level
            case UndoDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case ConfirmDepositBTC(
                  value
                ) =>
              import xyz.stratalab.sdk.syntax._
              for {
                _ <- trace"Deposit has been confirmed"
                someSessionInfo <- standardResponse(
                  request.clientNumber,
                  request.timestamp,
                  value.sessionId,
                  request.operation
                )
                _ <- trace"Minting: ${BigInt(value.amount.toByteArray())}"
                _ <- someSessionInfo
                  .flatMap(sessionInfo =>
                    MiscUtils.sessionInfoPeginPrism
                      .getOption(sessionInfo)
                      .map(peginSessionInfo =>
                        startMintingProcess[F](
                          defaultFromFellowship,
                          defaultFromTemplate,
                          peginSessionInfo.redeemAddress,
                          BigInt(value.amount.toByteArray())
                        )
                      )
                  )
                  .getOrElse(Sync[F].unit)
              } yield Result.Empty
            case PostTBTCMint(
                  value
                ) => // FIXME: add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case TimeoutTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              state.update(_ - value.sessionId) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> sendResponse(
                request.clientNumber,
                request.timestamp
              ) // FIXME: this is just a change of state at db level
            case UndoTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case ConfirmTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case PostRedemptionTx(
                  value
                ) => // FIXME: Add checks before executing
              for {
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
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case UndoClaimTx(value) =>
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case ConfirmClaimTx(value) =>
              state.update(_ - value.sessionId) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateSuccessfulPegin
              ) >> sendResponse(
                request.clientNumber,
                request.timestamp
              ) // FIXME: this is just a change of state at db level
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

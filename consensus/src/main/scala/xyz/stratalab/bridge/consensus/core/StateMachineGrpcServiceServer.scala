package xyz.stratalab.bridge.consensus.core

import cats.effect.IO
import cats.effect.kernel.{Ref, Sync}
import com.google.protobuf.ByteString
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import xyz.stratalab.bridge.consensus.core.pbft.{RequestIdentifier, RequestTimerManager, ViewManager}
import xyz.stratalab.bridge.consensus.core.{LastReplyMap, PublicApiClientGrpcMap}
import xyz.stratalab.bridge.consensus.pbft.{PrePrepareRequest, PrepareRequest}
import xyz.stratalab.bridge.consensus.service.MintingStatusReply.{Result => MSReply}
import xyz.stratalab.bridge.consensus.service.{
  MintingStatusReply,
  MintingStatusRes,
  SessionNotFoundRes,
  StateMachineServiceFs2Grpc
}
import xyz.stratalab.bridge.consensus.shared.PeginSessionInfo
import xyz.stratalab.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import xyz.stratalab.bridge.shared.{BridgeCryptoUtils, ClientId, Empty, MintingStatusOperation, ReplicaCount, ReplicaId}
import xyz.stratalab.consensus.core.PBFTInternalGrpcServiceClient

import java.security.{KeyPair => JKeyPair, MessageDigest}

object StateMachineGrpcServiceServer {

  def stateMachineGrpcServiceServer(
    keyPair:                JKeyPair,
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[IO],
    idReplicaClientMap:     Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
    currentSequenceRef:     Ref[IO, Long]
  )(implicit
    lastReplyMap:           LastReplyMap,
    requestTimerManager:    RequestTimerManager[IO],
    sessionManager:         SessionManagerAlgebra[IO],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
    viewManager:            ViewManager[IO],
    replicaId:              ReplicaId,
    replicaCount:           ReplicaCount,
    logger:                 Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {

      // log4cats syntax
      import org.typelevel.log4cats.syntax._
      import cats.implicits._

      private def mintingStatusAux[F[_]: Sync](
        value: MintingStatusOperation
      )(implicit
        sessionManager: SessionManagerAlgebra[F]
      ) =
        for {
          session <- sessionManager.getSession(value.sessionId)
          somePegin <- session match {
            case Some(p: PeginSessionInfo) => Sync[F].delay(Option(p))
            case None                      => Sync[F].delay(None)
            case _ =>
              Sync[F].raiseError(new Exception("Invalid session type"))
          }
          resp: MSReply = somePegin match {
            case Some(pegin) =>
              MSReply.MintingStatus(
                MintingStatusRes(
                  sessionId = value.sessionId,
                  mintingStatus = pegin.mintingBTCState.toString(),
                  address = pegin.redeemAddress,
                  redeemScript =
                    s""""threshold(1, sha256(${pegin.sha256}) and height(${pegin.minHeight}, ${pegin.maxHeight}))"""
                )
              )
            case None =>
              MSReply.SessionNotFound(
                SessionNotFoundRes(
                  value.sessionId
                )
              )
          }
        } yield resp

      override def mintingStatus(
        request: MintingStatusOperation,
        ctx:     Metadata
      ): IO[MintingStatusReply] =
        mintingStatusAux[IO](request).map(MintingStatusReply(_))

      def executeRequest(
        request: xyz.stratalab.bridge.shared.StateMachineRequest,
        ctx:     Metadata
      ): IO[Empty] =
        Option(
          lastReplyMap.underlying.get(
            (ClientId(request.clientNumber), request.timestamp)
          )
        ) match {
          case Some(result) => // we had a cached response
            for {
              viewNumber <- viewManager.currentView
              _          <- debug"Request.clientNumber: ${request.clientNumber}"
              _ <- publicApiClientGrpcMap
                .underlying(ClientId(request.clientNumber))
                ._1
                .replyStartPegin(request.timestamp, viewNumber, result)
            } yield Empty()
          case None =>
            for {
              currentView     <- viewManager.currentView
              currentSequence <- currentSequenceRef.updateAndGet(_ + 1)
              currentPrimary = currentView % replicaCount.value
              _ <-
                if (currentPrimary != replicaId.id)
                  // we are not the primary, forward the request
                  requestTimerManager.startTimer(
                    RequestIdentifier(
                      ClientId(request.clientNumber),
                      request.timestamp
                    )
                  ) >>
                  idReplicaClientMap(
                    replicaId.id
                  ).executeRequest(request, ctx)
                else {
                  import xyz.stratalab.bridge.shared.implicits._
                  val prePrepareRequest = PrePrepareRequest(
                    viewNumber = currentView,
                    sequenceNumber = currentSequence,
                    digest = ByteString.copyFrom(
                      MessageDigest
                        .getInstance("SHA-256")
                        .digest(request.signableBytes)
                    ),
                    payload = Some(request)
                  )
                  val prepareRequest = PrepareRequest(
                    viewNumber = currentView,
                    sequenceNumber = currentSequence,
                    digest = prePrepareRequest.digest,
                    replicaId = replicaId.id
                  )
                  (for {
                    signedPrePreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prePrepareRequest.signableBytes
                    )
                    signedPreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prepareRequest.signableBytes
                    )
                    signedprePrepareRequest = prePrepareRequest.withSignature(
                      ByteString.copyFrom(signedPrePreparedBytes)
                    )
                    signedPrepareRequest = prepareRequest.withSignature(
                      ByteString.copyFrom(signedPreparedBytes)
                    )
                    _ <- pbftProtocolClientGrpc.prePrepare(
                      signedprePrepareRequest
                    )
                  } yield ()) >> IO.pure(Empty())

                }
            } yield Empty()

        }
    }
  )

}

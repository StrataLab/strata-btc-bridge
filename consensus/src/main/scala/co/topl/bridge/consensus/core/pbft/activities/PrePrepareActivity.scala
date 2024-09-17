package co.topl.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import cats.implicits._
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.pbft.PrePreparedInserted
import co.topl.bridge.consensus.core.pbft.RequestStateManager
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.implicits._
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import org.typelevel.log4cats.Logger

import java.security.MessageDigest
import java.security.PublicKey

object PrePrepareActivity {

  private sealed trait PreprepareProblem extends Throwable
  private case object InvalidPrepreareSignature extends PreprepareProblem
  private case object InvalidRequestSignature extends PreprepareProblem
  private case object InvalidView extends PreprepareProblem
  private case object LogAlreadyExists extends PreprepareProblem

  private def checkDigest[F[_]: Async](
      requestDigest: Array[Byte],
      payloadSignableBytes: Array[Byte]
  ): F[Unit] = {
    val isValidDigest =
      Encoding.encodeToHex(requestDigest) == Encoding.encodeToHex(
        MessageDigest
          .getInstance("SHA-256")
          .digest(payloadSignableBytes)
      )
    Async[F].raiseUnless(isValidDigest)(
      InvalidRequestSignature
    )
  }

  private def checkRequestSignatures[F[_]: Async](
      request: PrePrepareRequest
  )(implicit publicApiClientGrpcMap: PublicApiClientGrpcMap[F]): F[Unit] = {
    val publicKey = publicApiClientGrpcMap
      .underlying(new ClientId(request.payload.get.clientNumber))
      ._2
    BridgeCryptoUtils.verifyBytes[F](
      publicKey,
      request.payload.get.signableBytes,
      request.payload.get.signature.toByteArray()
    ) >>= (x =>
      Async[F].raiseUnless(x)(
        InvalidRequestSignature
      )
    )
  }

  private def checkViewNumber[F[_]: Async](
      requestViewNumber: Long
  )(implicit currentViewRef: CurrentViewRef[F]): F[Unit] = {
    for {
      currentView <- currentViewRef.underlying.get
      isValidViewNumber = requestViewNumber == currentView
      _ <- Async[F].raiseUnless(isValidViewNumber)(
        InvalidView
      )
    } yield ()
  }

  private def checkWaterMark[F[_]: Async]()
      : F[Unit] = // FIXME: add check when watermarks are implemented
    ().pure[F]

  private def checkMessageSignaturePrimary[F[_]: Async](
      replicaKeysMap: Map[Int, PublicKey],
      requestSignableBytes: Array[Byte],
      requestSignature: Array[Byte]
  )(implicit
      currentViewRef: CurrentViewRef[F],
      replicaCount: ReplicaCount
  ): F[Boolean] = {
    import cats.implicits._
    for {
      currentView <- currentViewRef.underlying.get
      currentPrimary = (currentView % replicaCount.value).toInt
      publicKey = replicaKeysMap(currentPrimary)
      isValidSignature <- BridgeCryptoUtils.verifyBytes[F](
        publicKey,
        requestSignableBytes,
        requestSignature
      )
      _ <-
        Async[F].raiseUnless(isValidSignature)(
          InvalidPrepreareSignature
        )
    } yield isValidSignature
  }

  def apply[F[_]: Async: Logger](
      request: PrePrepareRequest
  )(
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      requestStateManager: RequestStateManager[F],
      currentViewRef: CurrentViewRef[F],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount
  ): F[Unit] = {
    import org.typelevel.log4cats.syntax._
    (for {
      _ <- trace"Received pre-prepare request"
      _ <- checkRequestSignatures(request)
      _ <- checkMessageSignaturePrimary(
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <- checkDigest(
        request.digest.toByteArray(),
        request.payload.get.signableBytes
      )
      _ <- checkViewNumber(request.viewNumber)
      _ <- checkWaterMark()
      canInsert <- storageApi
        .getPrePrepareMessage(request.viewNumber, request.sequenceNumber)
        .map(x =>
          x.map(y =>
            Encoding.encodeToHex(y.digest.toByteArray()) == Encoding
              .encodeToHex(request.digest.toByteArray())
          ).getOrElse(true)
        )
      _ <- Async[F].raiseUnless(canInsert)(
        LogAlreadyExists
      )
      _ <- storageApi.insertPrePrepareMessage(request)
      _ <- requestStateManager.createStateMachine(
        request.viewNumber,
        request.sequenceNumber
      )
      _ <- requestStateManager.processEvent(
        PrePreparedInserted(request)
      )
    } yield ()).handleErrorWith(_ match {
      case InvalidPrepreareSignature =>
        error"Invalid pre-prepare signature"
      case InvalidRequestSignature =>
        error"Invalid request signature"
      case InvalidView =>
        warn"Invalid view number"
      case LogAlreadyExists =>
        warn"Log already exists"
      case e =>
        error"Error handling pre-prepare request: $e"
    })
  }
}

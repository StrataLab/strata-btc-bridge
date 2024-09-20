package co.topl.bridge.consensus.core.pbft
import cats.effect.kernel.Async
import cats.implicits._
import co.topl.bridge.shared.BridgeCryptoUtils

import java.security.PublicKey
import cats.effect.kernel.Sync
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.shared.ClientId

import co.topl.bridge.shared.implicits._
import java.security.MessageDigest
import co.topl.brambl.utils.Encoding

package object activities {

  private[activities] def checkViewNumber[F[_]: Async](
      requestViewNumber: Long
  )(implicit viewManager: ViewManager[F]): F[Boolean] = {
    for {
      currentView <- viewManager.currentView
      isValidViewNumber = requestViewNumber == currentView
    } yield isValidViewNumber
  }

  private[activities] def isPrepared[F[_]: Sync](
      viewNumber: Long,
      sequenceNumber: Long
  )(implicit replicaCount: ReplicaCount, storageApi: StorageApi[F]) = {
    import cats.implicits._
    for {
      somePrePrepareMessage <- storageApi.getPrePrepareMessage(
        viewNumber,
        sequenceNumber
      )
      prepareMessages <- storageApi.getPrepareMessages(
        viewNumber,
        sequenceNumber
      )
    } yield somePrePrepareMessage.isDefined &&
      somePrePrepareMessage
        .map(prePrepareMessage =>
          (prepareMessages
            .filter(x =>
              x.digest.toByteArray
                .sameElements(prePrepareMessage.digest.toByteArray())
            )
            .size > replicaCount.maxFailures)
        )
        .getOrElse(false)
  }

  private[activities] def isCommitted[F[_]: Sync](
      viewNumber: Long,
      sequenceNumber: Long
  )(implicit replicaCount: ReplicaCount, storageApi: StorageApi[F]) = {
    import cats.implicits._
    for {
      somePrePrepareMessage <- storageApi.getPrePrepareMessage(
        viewNumber,
        sequenceNumber
      )
      commitMessages <- storageApi.getCommitMessages(
        viewNumber,
        sequenceNumber
      )
    } yield somePrePrepareMessage.isDefined &&
      somePrePrepareMessage
        .map(prePrepareMessage =>
          (commitMessages
            .filter(x =>
              x.digest.toByteArray
                .sameElements(prePrepareMessage.digest.toByteArray())
            )
            .size > replicaCount.maxFailures)
        )
        .getOrElse(false)
  }

  private[activities] def checkWaterMark[F[_]: Async]()
      : F[Boolean] = // FIXME: add check when watermarks are implemented
    true.pure[F]

  private[activities] def checkMessageSignature[F[_]: Async](
      replicaId: Int,
      replicaKeysMap: Map[Int, PublicKey],
      requestSignableBytes: Array[Byte],
      requestSignature: Array[Byte]
  ): F[Boolean] = {
    import cats.implicits._
    val publicKey = replicaKeysMap(replicaId)
    for {
      isValidSignature <- BridgeCryptoUtils.verifyBytes[F](
        publicKey,
        requestSignableBytes,
        requestSignature
      )
    } yield isValidSignature
  }

  private[activities] def checkRequestSignatures[F[_]: Async](
      request: PrePrepareRequest
  )(implicit publicApiClientGrpcMap: PublicApiClientGrpcMap[F]): F[Boolean] = {
    val publicKey = publicApiClientGrpcMap
      .underlying(new ClientId(request.payload.get.clientNumber))
      ._2
    BridgeCryptoUtils.verifyBytes[F](
      publicKey,
      request.payload.get.signableBytes,
      request.payload.get.signature.toByteArray()
    )
  }

  private[activities] def checkMessageSignaturePrimary[F[_]: Async](
      replicaKeysMap: Map[Int, PublicKey],
      requestSignableBytes: Array[Byte],
      requestSignature: Array[Byte]
  )(implicit
      viewManager: ViewManager[F],
      replicaCount: ReplicaCount
  ): F[Boolean] = {
    import cats.implicits._
    for {
      currentView <- viewManager.currentView
      currentPrimary = (currentView % replicaCount.value).toInt
      publicKey = replicaKeysMap(currentPrimary)
      isValidSignature <- BridgeCryptoUtils.verifyBytes[F](
        publicKey,
        requestSignableBytes,
        requestSignature
      )
    } yield isValidSignature
  }

  private[activities] def checkDigest[F[_]](
      requestDigest: Array[Byte],
      payloadSignableBytes: Array[Byte]
  ): Boolean = {
    Encoding.encodeToHex(requestDigest) == Encoding.encodeToHex(
      MessageDigest
        .getInstance("SHA-256")
        .digest(payloadSignableBytes)
    )
  }

}

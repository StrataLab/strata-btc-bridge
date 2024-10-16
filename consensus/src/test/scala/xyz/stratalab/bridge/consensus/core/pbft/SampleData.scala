package xyz.stratalab.bridge.consensus.core.pbft

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency.CurrencyUnit
import xyz.stratalab.bridge.consensus.core.{CheckpointInterval, Fellowship, KWatermark, LastReplyMap, Template}
import xyz.stratalab.bridge.consensus.shared.{BTCWaitExpirationTime, Lvl, StrataWaitExpirationTime}
import xyz.stratalab.bridge.shared.{ReplicaCount, ReplicaId}
import xyz.stratalab.sdk.models.{GroupId, SeriesId}
import xyz.stratalab.sdk.syntax._
import xyz.stratalab.sdk.utils.Encoding

import java.util.concurrent.ConcurrentHashMap

trait SampleData {

  import org.bitcoins.core.currency.SatoshisLong

  val privateKeyFile = "privateKey1.pem"

  val toplHost = "localhost"
  val toplPort = 9084
  val toplSecureConnection = false

  implicit val replicaCount: ReplicaCount = new ReplicaCount(7)

  implicit val kWatermark: KWatermark = new KWatermark(200)

  implicit val checkpointInterval: CheckpointInterval = new CheckpointInterval(
    100
  )

  implicit val replicaId: ReplicaId = new ReplicaId(1)

  val toplWalletFile = "src/test/resources/strata-wallet.json"

  val testStrataPassword = "test"

  val btcUser = "user"
  val btcPassword = "password"

  val btcNetwork = RegTest

  val btcUrl = "http://localhost:18332"

  implicit val toplWaitExpirationTime: StrataWaitExpirationTime =
    new StrataWaitExpirationTime(1000)

  implicit val btcWaitExpirationTime: BTCWaitExpirationTime =
    new BTCWaitExpirationTime(100)

  implicit val defaultMintingFee: Lvl = Lvl(100)

  implicit val astReplyMap: LastReplyMap = new LastReplyMap(
    new ConcurrentHashMap()
  )

  implicit val defaultFromFellowship: Fellowship = new Fellowship("default")

  implicit val defaultFromTemplate: Template = new Template("default")

  implicit val defaultFeePerByte: CurrencyUnit = 2.sats

  implicit val groupIdIdentifier: GroupId = GroupId(
    ByteString.copyFrom(
      Encoding
        .decodeFromHex(
          "a02be091b487960668958b39168e122210a8d5f5464deffb69ffebb3b2cfa131"
        )
        .toOption
        .get
    )
  )

  implicit val seriesIdIdentifier: SeriesId = SeriesId(
    ByteString.copyFrom(
      Encoding
        .decodeFromHex(
          "f323dd59469b53faf7fde28d234f6f1acc8c43405e976c7eec4a388e66c82479"
        )
        .toOption
        .get
    )
  )

  val conf = ConfigFactory.parseString(
    """
      |bridge.replica.consensus.replicas {
      |  0 {
      |    publicKeyFile = "publicKey0.pem"
      |  }
      |  1 {
      |    publicKeyFile = "publicKey1.pem"
      |  }
      |  2 {
      |    publicKeyFile = "publicKey2.pem"
      |  }
      |  3 {
      |    publicKeyFile = "publicKey3.pem"
      |  }
      |  4 {
      |    publicKeyFile = "publicKey4.pem"
      |  }
      |  5 {
      |    publicKeyFile = "publicKey5.pem"
      |  }
      |  6 {
      |    publicKeyFile = "publicKey6.pem"
      |  }
      |}
      |""".stripMargin
  )

}

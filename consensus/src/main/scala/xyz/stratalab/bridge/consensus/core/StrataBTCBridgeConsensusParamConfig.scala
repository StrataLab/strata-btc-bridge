package xyz.stratalab.bridge.consensus.core

import org.bitcoins.core.currency.{CurrencyUnit, SatoshisLong}
import xyz.stratalab.sdk.models.{GroupId, SeriesId}

import java.io.File

case class StrataBTCBridgeConsensusParamConfig(
  btcWaitExpirationTime:     Int = 100, // the number of blocks to wait before the user can reclaim their funds
  btcConfirmationThreshold:  Int = 6, // the number of confirmations required for a peg-in transaction
  toplWaitExpirationTime:    Int = 2000, // the number of blocks to wait before the user can reclaim their funds
  toplConfirmationThreshold: Int = 6, // the number of confirmations required for a peg-out transaction
  checkpointInterval:        Int = 100, // the number of requests between checkpoints
  requestTimeout:            Int = 15, // the timeout for requests in seconds
  viewChangeTimeout:         Int = 5, // the timeout for view changes in seconds
  kWatermark:                Int = 200, // the gap between the low and high watermark
  btcPegInSeedFile:          String = "pegin-wallet.json",
  btcPegInPassword:          String = "password",
  btcWalletSeedFile:         String = "wallet.json",
  walletPassword:            String = "password",
  toplWalletSeedFile:        String = "strata-wallet.json",
  toplWalletPassword:        String = "password",
  toplWalletDb:              String = "strata-wallet.db",
  btcUrl:                    String = "http://localhost",
  btcUser:                   String = "bitcoin",
  zmqHost:                   String = "localhost",
  zmqPort:                   Int = 28332,
  btcPassword:               String = "password",
  btcNetwork:                BitcoinNetworkIdentifiers = RegTest,
  toplNetwork:               StrataNetworkIdentifiers = StrataPrivatenet,
  toplHost:                  String = "localhost",
  toplPort:                  Int = 9084,
  btcRetryThreshold:         Int = 6,
  mintingFee:                Long = 10,
  feePerByte:                CurrencyUnit = 2.satoshis,
  groupId:                   GroupId,
  seriesId:                  SeriesId,
  configurationFile:         File = new File("application.conf"),
  dbFile:                    File = new File("bridge.db"),
  toplSecureConnection:      Boolean = false
)

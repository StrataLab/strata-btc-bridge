package xyz.stratalab.bridge.stubs

import cats.effect.IO
import xyz.stratalab.sdk.dataApi.GenusQueryAlgebra
import xyz.stratalab.sdk.models.LockAddress
import xyz.stratalab.indexer.services.{Txo, TxoState}

class BaseGenusQueryAlgebra extends GenusQueryAlgebra[IO] {

  override def queryUtxo(
    fromAddress: LockAddress,
    txoState:    TxoState
  ): IO[Seq[Txo]] = ???

}

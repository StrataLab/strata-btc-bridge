package co.topl.bridge.consensus.core

import java.security.MessageDigest
import pbft.statemachine.PBFTState

package object pbft {

  def createStateDigestAux(
      state: Map[String, PBFTState]
  ) = {
    val stateBytes = state.toList
      .sortBy(_._1)
      .map(x => x._1.getBytes ++ x._2.toBytes)
      .flatten
    MessageDigest
      .getInstance("SHA-256")
      .digest(stateBytes.toArray)
  }

  def createStateDigest(
      state: SessionState
  ) = {
    // import JavaConverters
    import scala.jdk.CollectionConverters._
    val stateBytes = createStateDigestAux(
      state.underlying.entrySet.asScala.toList
        .map(x => x.getKey -> x.getValue)
        .toMap
    )
    MessageDigest
      .getInstance("SHA-256")
      .digest(stateBytes.toArray)
  }

}

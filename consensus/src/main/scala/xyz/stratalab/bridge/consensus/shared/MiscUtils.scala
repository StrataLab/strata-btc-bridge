package xyz.stratalab.bridge.consensus.shared

import xyz.stratalab.bridge.consensus.shared.SessionInfo
import xyz.stratalab.bridge.consensus.shared.PeginSessionInfo

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}

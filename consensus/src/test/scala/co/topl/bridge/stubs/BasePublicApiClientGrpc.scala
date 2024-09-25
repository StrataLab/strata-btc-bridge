package co.topl.bridge.stubs

import co.topl.bridge.consensus.core.PublicApiClientGrpc
import cats.effect.IO
import xyz.stratalab.bridge.consensus.service.StateMachineReply
import xyz.stratalab.bridge.shared.Empty

class BasePublicApiClientGrpc extends PublicApiClientGrpc[IO] {

  override def replyStartPegin(
      timestamp: Long,
      currentView: Long,
      startSessionRes: StateMachineReply.Result
  ): IO[Empty] = ???

}

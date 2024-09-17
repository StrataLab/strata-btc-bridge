package co.topl.bridge.stubs

import co.topl.bridge.consensus.core.pbft.RequestStateManager
import cats.effect.IO
import co.topl.bridge.consensus.core.pbft.StateMachineEvent
import co.topl.bridge.shared.ReplicaId
import co.topl.consensus.core.PBFTInternalGrpcServiceClient

class BaseRequestStateManager extends RequestStateManager[IO] {

  def createStateMachine(viewNumber: Long, sequenceNumber: Long): IO[Unit] =
    IO.unit

  def processEvent(
      event: StateMachineEvent
  )(implicit
      replica: ReplicaId,
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[IO]
  ): IO[Unit] = IO.unit

}

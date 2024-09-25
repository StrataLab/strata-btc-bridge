package co.topl.bridge.stubs

import co.topl.bridge.consensus.core.pbft.RequestStateManager
import cats.effect.IO
import co.topl.bridge.consensus.core.pbft.RequestIdentifier
import cats.effect.kernel.{Outcome, Resource}
import scala.annotation.nowarn

class BaseRequestStateManager extends RequestStateManager[IO] {

  override def createStateMachine(
      requestIdentifier: RequestIdentifier
  ): IO[Unit] = ???

  override def startProcessingEvents()
      : Resource[IO, IO[Outcome[IO, Throwable, Unit]]] = ???

  @nowarn
  def createStateMachine(viewNumber: Long, sequenceNumber: Long): IO[Unit] =
    IO.unit

}

package co.topl.bridge.stubs

import cats.effect.IO
import co.topl.bridge.consensus.core.pbft.RequestIdentifier
import co.topl.bridge.consensus.core.pbft.RequestTimerManager

class BaseRequestTimerManager extends RequestTimerManager[IO] {

  def startTimer(timerIdentifier: RequestIdentifier): IO[Unit] =
    IO.unit

  def clearTimer(timerIdentifier: RequestIdentifier): IO[Unit] =
    IO.unit

  def hasExpiredTimer(): IO[Boolean] =
    IO.pure(false)

  def resetAllTimers(): IO[Unit] =
    IO.unit
}

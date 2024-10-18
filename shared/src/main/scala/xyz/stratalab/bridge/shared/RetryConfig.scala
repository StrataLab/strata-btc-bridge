package xyz.stratalab.bridge.shared

import scala.concurrent.duration.FiniteDuration

sealed abstract class StateMachineServiceGrpcClientRetryConfig (
  val initialSleep: Int, 
  val finalSleep: Int, 
  val initialDelay: Int, 
  val maxRetries: Int
) {
  def getInitialSleep: FiniteDuration = FiniteDuration.apply(initialSleep, "second")

  def getFinalSleep: FiniteDuration = FiniteDuration.apply(finalSleep, "second")

  def getInitialDelay: FiniteDuration = FiniteDuration.apply(initialDelay, "second")

  def getMaxRetries: Int = maxRetries

  override def toString: String = s"Initial Sleep: ${initialSleep}, Final Sleep: ${finalSleep}, initialDelay: ${initialDelay}, maxRetries: ${maxRetries}"
}
case class StateMachineServiceGrpcClientRetryConfigImpl(
  override val initialSleep: Int,
  override val finalSleep: Int,
  override val initialDelay: Int,
  override val maxRetries: Int
) extends StateMachineServiceGrpcClientRetryConfig(initialSleep, finalSleep, initialDelay, maxRetries)

sealed abstract class PBFTInternalGrpcServiceClientRetryConfig (
  val initialDelay: Int, 
  val maxRetries: Int
) {
  def getInitialDelay: FiniteDuration = FiniteDuration.apply(initialDelay, "second")
  def getMaxRetries: Int = maxRetries
  override def toString: String = s"initialDelay: ${initialDelay}, maxRetries: ${maxRetries}"
}

case class PBFTInternalGrpcServiceClientRetryConfigImpl(
  override val initialDelay: Int,
  override val maxRetries: Int
) extends PBFTInternalGrpcServiceClientRetryConfig(initialDelay, maxRetries)
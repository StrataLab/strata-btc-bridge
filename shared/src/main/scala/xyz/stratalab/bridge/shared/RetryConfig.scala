package xyz.stratalab.bridge.shared

import scala.concurrent.duration.FiniteDuration

sealed abstract class StateMachineServiceGrpcClientRetryConfig (
  val initialSleep: FiniteDuration, 
  val finalSleep: FiniteDuration, 
  val initialDuration: FiniteDuration, 
  val maxRetries: Int
) {
  def getInitialSleep: FiniteDuration = initialSleep

  def getFinalSleep: FiniteDuration = finalSleep

  def getInitialDuration: FiniteDuration = initialDuration

  def getMaxRetries: Int = maxRetries

  override def toString: String = s"Initial Sleep: ${initialSleep}, Final Sleep: ${finalSleep}, intitalDuration: ${initialDuration}, maxRetries: ${maxRetries}"
}

sealed abstract class PBFTInternalGrpcServiceClientRetryConfig (
  val initialDelay: FiniteDuration, 
  val maxRetries: Int
) {

  def getInitialDelay: FiniteDuration = initialDelay
  def getMaxRetries: Int = maxRetries
  override def toString: String = s"IntitalDuration: ${initialDelay}, maxRetries: ${maxRetries}"
}

object PbftInternalClientConfig extends PBFTInternalGrpcServiceClientRetryConfig(FiniteDuration(1, "second"), 1)
object StateMachineClientConfig extends StateMachineServiceGrpcClientRetryConfig(FiniteDuration.apply(10, "second"), FiniteDuration.apply(10, "second"), FiniteDuration.apply(1, "second"), 3)
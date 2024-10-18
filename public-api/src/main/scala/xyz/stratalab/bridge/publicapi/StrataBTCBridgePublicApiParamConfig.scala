package xyz.stratalab.bridge.publicapi

import xyz.stratalab.bridge.shared.{StateMachineClientConfig, StateMachineServiceGrpcClientRetryConfig}

import java.io.File

case class StrataBTCBridgePublicApiParamConfig(
  configurationFile:        File = new File("application.conf"),
  stateMachineClientConfig: StateMachineServiceGrpcClientRetryConfig = StateMachineClientConfig
)

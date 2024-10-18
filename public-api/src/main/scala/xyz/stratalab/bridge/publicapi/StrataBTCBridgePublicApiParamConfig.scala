package xyz.stratalab.bridge.publicapi

import java.io.File
import xyz.stratalab.bridge.shared.{StateMachineServiceGrpcClientRetryConfig, StateMachineClientConfig}


case class StrataBTCBridgePublicApiParamConfig(
  configurationFile: File = new File("application.conf"),
  stateMachineClientConfig:  StateMachineServiceGrpcClientRetryConfig = StateMachineClientConfig
)

package xyz.stratalab.bridge

trait PublicApiConfModule extends CommonSetupModule {

  def publicApiConfString(clientId: Int, replicaCount: Int) = s"""
|bridge {
|  client {
|    # the unique number that identifies this client
|    # client ids are pair numbers starting from 0
|    clientId = ${clientId}
|    api {
|      # the host where we are listening for requests
|      host = "0.0.0.0"
|      # the port where we are listening for requests
|      port = ${apiPort + clientId}
|    }
|    responses {
|      # the host where we are listening for responses
|      host = "[::]"
|      # the port where we are listening for responses
|      port = ${clientPort + clientId}
|    } 
|    # security configuration
|    security {
|      # path to the public key file
|      publicKeyFile = "clientPublicKey${clientId}.pem"
|      # path to the private key file
|      privateKeyFile = "clientPrivateKey${clientId}.pem"
|    }
|    consensus {
|      replicaCount = ${replicaCount}
|      # map mapping each replica to its corresponding backend
|      replicas = {
${replicasConfString(replicaCount)}
|      }
|    }
|    stateMachine { 
|     # in seconds
|     intialSleep = 10 
|     # in seconds
|     finalSleep = 10 
|     # in seconds
|     initialDelay = 1
|     maxRetries = 3
|   }
|  }
|}
  """.trim().stripMargin

}

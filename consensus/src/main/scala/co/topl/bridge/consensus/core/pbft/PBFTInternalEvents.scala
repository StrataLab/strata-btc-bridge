package co.topl.bridge.consensus.core.pbft

sealed trait PBFTInternalEvent
case class PBFTTimeoutEvent(requestIdentifier: RequestIdentifier)
    extends PBFTInternalEvent

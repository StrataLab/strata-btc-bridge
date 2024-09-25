package co.topl.bridge.consensus.core.pbft

import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.core.pbft.statemachine.PBFTState

private[pbft] case class StableCheckpoint(
    sequenceNumber: Long,
    certificates: Map[Int, CheckpointRequest],
    state: Map[String, PBFTState]
)

private[pbft] case class UnstableCheckpoint(
    certificates: Map[Int, CheckpointRequest]
)

private[pbft] case class StateSnapshot(
    sequenceNumber: Long,
    digest: String,
    state: Map[String, PBFTState]
)

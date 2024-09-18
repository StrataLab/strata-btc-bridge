package co.topl.bridge.consensus.core.pbft

import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.ClientId

sealed abstract class PBFTInternalEvent(
    val requestIdentifier: RequestIdentifier
)

case class PrePreparedInserted(
    request: PrePrepareRequest
) extends PBFTInternalEvent(
      RequestIdentifier(
        ClientId(request.payload.get.clientNumber),
        request.payload.get.timestamp
      )
    )
case class Prepared(
    override val requestIdentifier: RequestIdentifier,
    request: PrepareRequest
) extends PBFTInternalEvent(requestIdentifier)
case class Commited(
    override val requestIdentifier: RequestIdentifier,
    request: CommitRequest
) extends PBFTInternalEvent(requestIdentifier)

case class PBFTTimeoutEvent(override val requestIdentifier: RequestIdentifier)
    extends PBFTInternalEvent(requestIdentifier)

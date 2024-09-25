package co.topl.bridge.stubs

import cats.effect.IO
import xyz.stratalab.bridge.consensus.pbft.CheckpointRequest
import xyz.stratalab.bridge.consensus.pbft.CommitRequest
import xyz.stratalab.bridge.consensus.pbft.PrePrepareRequest
import xyz.stratalab.bridge.consensus.pbft.PrepareRequest
import xyz.stratalab.bridge.shared.Empty
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import xyz.stratalab.bridge.consensus.pbft.ViewChangeRequest
import xyz.stratalab.bridge.consensus.pbft.NewViewRequest

class BasePBFTInternalGrpcServiceClient
    extends PBFTInternalGrpcServiceClient[IO] {

  override def newView(request: NewViewRequest): IO[Empty] = ???

  override def prePrepare(request: PrePrepareRequest): IO[Empty] = ???

  override def prepare(request: PrepareRequest): IO[Empty] = ???

  override def commit(request: CommitRequest): IO[Empty] = ???

  override def checkpoint(request: CheckpointRequest): IO[Empty] = ???

  override def viewChange(request: ViewChangeRequest): IO[Empty] = ???

}

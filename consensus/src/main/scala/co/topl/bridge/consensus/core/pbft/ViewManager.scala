package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Ref
import cats.effect.kernel.Async

trait ViewManager[F[_]] {

  def currentView: F[Long]

}

object ViewManagerImpl {
  import cats.implicits._

  def make[F[_]: Async](): F[ViewManager[F]] = {
    for {
      currentViewRef <- Ref.of[F, Long](0L)
    } yield new ViewManager[F] {

      override def currentView: F[Long] = currentViewRef.get

    }
  }
}

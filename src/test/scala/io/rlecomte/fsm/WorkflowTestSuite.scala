package io.rlecomte.fsm

import cats.effect.testing.minitest.IOTestSuite
import cats.effect.IO
import scala.reflect.ClassTag
import cats.effect.kernel.Resource

trait WorkflowTestSuite extends IOTestSuite {
  def checkPayloadM[T <: WorkflowEvent](
      event: Event
  )(f: T => IO[Unit])(implicit tag: ClassTag[T]): IO[Unit] =
    event.payload match {
      case p: T    => f(p)
      case payload => IO(fail(s"$payload isn't a ${tag.runtimeClass.getName()} event."))
    }

  def checkPayload[T <: WorkflowEvent](
      event: Event
  )(f: T => Unit)(implicit tag: ClassTag[T]): IO[Unit] =
    checkPayloadM[T](event)(e => IO.pure(f(e)))

  def checkPayload_[T <: WorkflowEvent](
      event: Event
  )(implicit tag: ClassTag[T]): IO[Unit] =
    checkPayloadM[T](event)(_ => IO.unit)

  def testW(name: String)(f: BackendEventStore => IO[Unit]): Unit =
    test(name)(backendResource.use(f))

  val backendResource: Resource[IO, BackendEventStore] =
    Resource.eval(InMemoryBackendEventStore.newStore)
}

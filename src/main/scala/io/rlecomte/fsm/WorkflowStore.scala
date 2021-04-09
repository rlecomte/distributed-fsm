package io.rlecomte.fsm

import cats.effect.IO
import cats.effect.kernel.Ref

trait BackendEventStore {
  def registerEvent(event: Event): IO[Unit]

  def readAllEvents: IO[List[Event]]

  def readEvents(runId: RunId): IO[List[Event]]
}

case class InMemoryBackendEventStore(ref: Ref[IO, Vector[Event]]) extends BackendEventStore {
  override def registerEvent(event: Event): IO[Unit] =
    ref.getAndUpdate(vec => vec.appended(event)).void

  override def readAllEvents: IO[List[Event]] = ref.get.map(_.toList)

  override def readEvents(runId: RunId): IO[List[Event]] =
    readAllEvents.map(_.filter(_.runId == runId))
}

object InMemoryBackendEventStore {
  val newStore: IO[InMemoryBackendEventStore] = for {
    refStore <- Ref.of[IO, Vector[Event]](Vector())
  } yield InMemoryBackendEventStore(refStore)
}

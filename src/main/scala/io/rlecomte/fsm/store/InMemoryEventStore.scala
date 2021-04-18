package io.rlecomte.fsm.store

import cats.effect.IO
import cats.effect.kernel.Ref
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.RunId

case class InMemoryEventStore(ref: Ref[IO, Vector[Event]]) extends EventStore {
  override def registerEvent(event: Event): IO[Unit] =
    ref.getAndUpdate(vec => vec.appended(event)).void

  override def readAllEvents: IO[List[Event]] = ref.get.map(_.toList)

  override def readEvents(runId: RunId): IO[List[Event]] =
    readAllEvents.map(_.filter(_.runId == runId))
}

object InMemoryEventStore {
  val newStore: IO[InMemoryEventStore] = for {
    refStore <- Ref.of[IO, Vector[Event]](Vector())
  } yield InMemoryEventStore(refStore)
}

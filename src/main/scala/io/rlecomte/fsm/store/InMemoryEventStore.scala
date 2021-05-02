package io.rlecomte.fsm.store

import cats.effect.IO
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.RunId
import java.util.UUID
import java.time.Instant
import io.rlecomte.fsm.WorkflowEvent
import io.rlecomte.fsm.EventId
import io.rlecomte.fsm.runtime.VersionConflict
import cats.effect.std.Queue

case class InMemoryEventStore(
    ref: Queue[IO, Vector[Event]]
) extends EventStore {

  override def unsafeRegisterEvent(
      runId: RunId,
      event: WorkflowEvent
  ): IO[EventId] = {
    for {
      events <- ref.take
      currentVersion = events
        .filter(_.runId == runId)
        .map[Version](_.seqNum)
        .maxOption
        .getOrElse(Version.empty)
      evtId <- IO(EventId(UUID.randomUUID()))
      inst <- IO(Instant.now())
      evt = Event(evtId, runId, Version.inc(currentVersion), inst, event)
      _ <- ref.offer(events.appended(evt))
    } yield evtId
  }

  override def registerEvent(
      runId: RunId,
      event: WorkflowEvent,
      expectedVersion: Version
  ): IO[Either[VersionConflict, EventId]] = {
    for {
      events <- ref.take
      currentVersion = events
        .filter(_.runId == runId)
        .map[Version](_.seqNum)
        .maxOption
        .getOrElse(Version.empty)
      evtId <- IO(EventId(UUID.randomUUID()))
      inst <- IO(Instant.now())
      evt = Event(evtId, runId, Version.inc(currentVersion), inst, event)
      result <-
        if (expectedVersion.value == currentVersion.value)
          ref.offer(events.appended(evt)).as(Right(evtId))
        else ref.offer(events).as(Left(VersionConflict(expectedVersion, currentVersion)))
    } yield result
  }

  override def readAllEvents: IO[List[Event]] =
    ref.take.bracket(v => IO(v.toList))(v => ref.offer(v))

  override def readEvents(runId: RunId): IO[List[Event]] =
    readAllEvents.map(_.filter(_.runId == runId))
}

object InMemoryEventStore {
  val newStore: IO[InMemoryEventStore] = for {
    ref <- Queue.bounded[IO, Vector[Event]](1)
    _ <- ref.offer(Vector())
  } yield InMemoryEventStore(ref)
}

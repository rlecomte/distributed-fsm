package io.rlecomte.fsm.store

import cats.effect.IO
import cats.effect.kernel.Ref
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.EventId
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.WorkflowEvent
import io.rlecomte.fsm.runtime.VersionConflict

import java.time.Instant
import java.util.UUID

case class InMemoryEventStore(
    ref: Ref[IO, Vector[Event]]
) extends EventStore {

  override def unsafeRegisterEvent(
      runId: RunId,
      event: WorkflowEvent
  ): IO[EventId] = {
    for {
      evtId <- IO(EventId(UUID.randomUUID()))
      inst <- IO(Instant.now())
      _ <- ref.update { events =>
        val currentVersion = events
          .filter(_.runId == runId)
          .map[Version](_.seqNum)
          .maxOption
          .getOrElse(Version.empty)

        val evt = Event(evtId, runId, Version.inc(currentVersion), inst, event)
        events.appended(evt)
      }
    } yield evtId
  }

  override def registerEvent(
      runId: RunId,
      event: WorkflowEvent,
      expectedVersion: Version
  ): IO[Either[VersionConflict, EventId]] = {
    for {
      evtId <- IO(EventId(UUID.randomUUID()))
      inst <- IO(Instant.now())
      result <- ref.modify { events =>
        val currentVersion = events
          .filter(_.runId == runId)
          .map[Version](_.seqNum)
          .maxOption
          .getOrElse(Version.empty)

        val evt = Event(evtId, runId, Version.inc(currentVersion), inst, event)

        if (expectedVersion.value == currentVersion.value)
          (events.appended(evt), Right(evtId))
        else (events, Left(VersionConflict(expectedVersion, currentVersion)))
      }
    } yield result
  }

  override def readAllEvents: IO[List[Event]] = ref.get.map(_.toList)

  override def readEvents(runId: RunId): IO[List[Event]] =
    readAllEvents.map(_.filter(_.runId == runId))
}

object InMemoryEventStore {
  val newStore: IO[InMemoryEventStore] = for {
    ref <- Ref.of[IO, Vector[Event]](Vector())
  } yield InMemoryEventStore(ref)
}

package io.rlecomte.fsm.store

import cats.effect.IO
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.EventId
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.WorkflowEvent
import io.rlecomte.fsm.runtime.VersionConflict

case class Version(value: Long)

object Version {
  val empty: Version = Version(0L)

  def inc(v: Version): Version = Version(v.value + 1)

  implicit val versionOrdering: Ordering[Version] = Ordering.Long.on(_.value)
}

trait EventStore {
  def registerEvent(
      runId: RunId,
      event: WorkflowEvent,
      expectedVersion: Version
  ): IO[Either[VersionConflict, EventId]]

  def unsafeRegisterEvent(
      runId: RunId,
      event: WorkflowEvent
  ): IO[EventId]

  def readAllEvents: IO[List[Event]]

  def readEvents(runId: RunId): IO[List[Event]]
}

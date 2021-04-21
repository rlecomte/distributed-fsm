package io.rlecomte.fsm.store

import cats.effect.IO
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.WorkflowEvent
import io.rlecomte.fsm.EventId

sealed trait Version
case object EmptyVersion extends Version
case class SeqNum(value: Int) extends Version

object Version {
  def inc(v: Version): SeqNum = v match {
    case EmptyVersion => SeqNum(0)
    case SeqNum(i)    => SeqNum(i + 1)
  }
}

case class VersionConflict(expected: Version, current: Version) extends Exception

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

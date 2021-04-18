package io.rlecomte.fsm.store

import cats.effect.IO
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.RunId

trait EventStore {
  def registerEvent(event: Event): IO[Unit]

  def readAllEvents: IO[List[Event]]

  def readEvents(runId: RunId): IO[List[Event]]
}

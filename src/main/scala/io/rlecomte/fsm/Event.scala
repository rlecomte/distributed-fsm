package io.rlecomte.fsm

import java.time.Instant
import java.util.UUID
import cats.effect.IO

case class Event(
    id: EventId = EventId(UUID.randomUUID()),
    runId: RunId,
    timestamp: Instant = Instant.now(),
    payload: WorkflowEvent
)

object Event {
  def newEvent(runId: RunId, payload: WorkflowEvent): IO[Event] = IO(
    Event(runId = runId, payload = payload)
  )
}

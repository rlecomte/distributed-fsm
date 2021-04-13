package io.rlecomte.fsm

import java.util.UUID
import io.circe.Json
import cats.effect.IO
import java.time.Instant

case class RunId(value: UUID) extends AnyVal

object RunId {
  val newRunId: IO[RunId] = IO(UUID.randomUUID()).map(RunId.apply)
}

case class WorkflowError(value: String) extends AnyVal

object WorkflowError {
  def fromThrowable(error: Throwable): WorkflowError = WorkflowError(
    error.toString()
  )
}

case class EventId(value: UUID) extends AnyVal
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

sealed trait WorkflowEvent

case class WorkflowStarted(workflow: String, input: Json) extends WorkflowEvent

case object WorkflowCompleted extends WorkflowEvent

case object WorkflowFailed extends WorkflowEvent

case class StepStarted(
    step: String,
    correlationId: EventId
) extends WorkflowEvent

case class StepCompleted(
    step: String,
    payload: Json
) extends WorkflowEvent

case class StepFailed(
    step: String,
    error: WorkflowError
) extends WorkflowEvent

case class SeqStarted(correlationId: EventId) extends WorkflowEvent

case class ParStarted(correlationId: EventId) extends WorkflowEvent

case class StepCompensationStarted(step: String) extends WorkflowEvent

case class StepCompensationCompleted(step: String) extends WorkflowEvent

case class StepCompensationFailed(step: String, error: WorkflowError) extends WorkflowEvent

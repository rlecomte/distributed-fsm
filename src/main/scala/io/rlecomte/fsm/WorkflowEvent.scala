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

// Push step 1
// Completed step 1
// PushPar step 2 "$correlationId"
//    Push "$correlationId" step 2-1
//    Completed step 2-1
//    Push "$correlationId" step 2-2
//    Completed step 2-2
// PopPar
// PushPar step 3 "$correlationId"
//    Push "$correlationId" step 3-1
//    Completed step 3-1
//    Push "$correlationId" step 3-2
//    Completed step 3-2
// PopPar
// Push step 4
// Failed step 4

sealed trait WorkflowEvent

case class WorkflowStarted(workflow: String) extends WorkflowEvent

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

case object ParStepStarted extends WorkflowEvent

case class ParStepCompleted(correlationId: EventId) extends WorkflowEvent

case class StepCompensationStarted(step: String) extends WorkflowEvent

case class StepCompensationCompleted(step: String) extends WorkflowEvent

case class StepCompensationFailed(step: String, error: WorkflowError) extends WorkflowEvent

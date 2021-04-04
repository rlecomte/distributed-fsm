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

sealed trait WorkflowEvent {
  val id: RunId
  val timestamp: Instant
}

case class WorkflowStarted(
    workflow: String,
    id: RunId,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent
case class WorkflowCompleted(id: RunId, timestamp: Instant = Instant.now())
    extends WorkflowEvent
case class WorkflowFailed(id: RunId, timestamp: Instant = Instant.now())
    extends WorkflowEvent
case class WorkflowStepStarted(
    step: String,
    id: RunId,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent
case class WorkflowStepCompleted(
    step: String,
    id: RunId,
    payload: Json,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent
case class WorkflowStepFailed(
    step: String,
    id: RunId,
    error: WorkflowError,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent
case class WorkflowCompensationStarted(
    step: String,
    id: RunId,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent
case class WorkflowCompensationCompleted(
    step: String,
    id: RunId,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent
case class WorkflowCompensationFailed(
    step: String,
    id: RunId,
    error: WorkflowError,
    timestamp: Instant = Instant.now()
) extends WorkflowEvent

package io.rlecomte.fsm

import java.util.UUID
import io.circe.Json
import cats.effect.IO

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

sealed trait WorkflowEvent
case class WorkflowStarted(workflow: String, runId: RunId) extends WorkflowEvent
case class WorkflowCompleted(runId: RunId) extends WorkflowEvent
case class WorkflowFailed(runId: RunId) extends WorkflowEvent
case class WorkflowStepStarted(step: String, id: RunId) extends WorkflowEvent
case class WorkflowStepCompleted(step: String, id: RunId, payload: Json)
    extends WorkflowEvent
case class WorkflowStepFailed(step: String, id: RunId, error: WorkflowError)
    extends WorkflowEvent
case class WorkflowCompensationStarted(step: String, id: RunId)
    extends WorkflowEvent
case class WorkflowCompensationCompleted(step: String, id: RunId)
    extends WorkflowEvent
case class WorkflowCompensationFailed(
    step: String,
    id: RunId,
    error: WorkflowError
) extends WorkflowEvent

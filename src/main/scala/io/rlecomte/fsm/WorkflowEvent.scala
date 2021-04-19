package io.rlecomte.fsm

import io.circe.Json

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

case class SuspendFirstStepStarted(step: String, correlationId: EventId) extends WorkflowEvent

case class SuspendFirstStepCompleted(step: String) extends WorkflowEvent

case class SuspendFirstStepFailed(step: String, error: WorkflowError) extends WorkflowEvent

case class SuspendSecondStepStarted(step: String) extends WorkflowEvent

case class SuspendSecondStepCompleted(step: String, payload: Json) extends WorkflowEvent

case class SuspendSecondStepFailed(step: String, error: WorkflowError) extends WorkflowEvent

case class StepFailed(
    step: String,
    error: WorkflowError
) extends WorkflowEvent

case class SeqStarted(correlationId: EventId) extends WorkflowEvent

case class ParStarted(correlationId: EventId) extends WorkflowEvent

case class StepCompensationStarted(step: String) extends WorkflowEvent

case class StepCompensationCompleted(step: String) extends WorkflowEvent

case class StepCompensationFailed(step: String, error: WorkflowError) extends WorkflowEvent

package io.rlecomte.fsm

import java.util.UUID

sealed trait WorkflowEvent
case class WorkflowStarted(workflow: String, runId: UUID) extends WorkflowEvent
case class WorkflowCompleted(runId: UUID) extends WorkflowEvent
case class WorkflowFailed(runId: UUID) extends WorkflowEvent
case class WorkflowStepStarted(step: String, id: UUID) extends WorkflowEvent
case class WorkflowStepCompleted(step: String, id: UUID, payload: String)
    extends WorkflowEvent
case class WorkflowStepFailed(step: String, id: UUID, error: Throwable)
    extends WorkflowEvent
case class WorkflowCompensationStarted(step: String, id: UUID)
    extends WorkflowEvent
case class WorkflowCompensationCompleted(step: String, id: UUID)
    extends WorkflowEvent
case class WorkflowCompensationFailed(step: String, id: UUID, error: Throwable)
    extends WorkflowEvent

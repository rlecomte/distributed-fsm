package io.rlecomte.fsm.runtime

import io.rlecomte.fsm.WorkflowStarted
import io.rlecomte.fsm.WorkflowCompleted
import io.rlecomte.fsm.SeqStarted
import io.rlecomte.fsm.ParStarted
import io.rlecomte.fsm.store.EventStore
import io.rlecomte.fsm.EventId
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.WorkflowFailed
import io.rlecomte.fsm.Workflow.Step
import io.rlecomte.fsm.StepStarted
import io.rlecomte.fsm.StepCompleted
import io.rlecomte.fsm.StepFailed
import io.rlecomte.fsm.WorkflowError
import cats.effect.IO
import io.circe.Encoder
import io.rlecomte.fsm.StepCompensationStarted
import io.rlecomte.fsm.StepCompensationFailed
import io.rlecomte.fsm.StepCompensationCompleted
import io.rlecomte.fsm.store.VersionConflict
import io.rlecomte.fsm.CompensationStarted
import io.rlecomte.fsm.CompensationCompleted
import io.rlecomte.fsm.CompensationFailed
import io.rlecomte.fsm.store.Version

object EventLogger {

  def logWorkflowStarted[I](
      backend: EventStore,
      name: String,
      runId: RunId,
      input: I,
      expectedVersion: Version
  )(encoder: Encoder[I]): IO[Either[VersionConflict, EventId]] =
    backend.registerEvent(runId, WorkflowStarted(name, encoder(input)), expectedVersion)

  def logWorkflowCompleted(
      backend: EventStore,
      runId: RunId
  ): IO[EventId] = backend.unsafeRegisterEvent(runId, WorkflowCompleted)

  def logSeqStarted(backend: EventStore, runId: RunId, parentId: EventId): IO[EventId] =
    backend.unsafeRegisterEvent(runId, SeqStarted(parentId))

  def logParStarted(
      backend: EventStore,
      runId: RunId,
      parentId: EventId
  ): IO[EventId] = backend.unsafeRegisterEvent(runId, ParStarted(parentId))

  def logWorkflowFailed(
      backend: EventStore,
      runId: RunId
  ): IO[EventId] = backend.unsafeRegisterEvent(runId, WorkflowFailed)

  def logStepStarted(
      backend: EventStore,
      runId: RunId,
      step: Step[_],
      parentId: EventId
  ): IO[EventId] =
    backend.unsafeRegisterEvent(
      runId,
      StepStarted(step.name, parentId)
    )

  def logStepCompleted[A](
      backend: EventStore,
      runId: RunId,
      step: Step[A],
      result: A
  )(implicit encoder: Encoder[A]): IO[EventId] =
    backend.unsafeRegisterEvent(
      runId,
      StepCompleted(step.name, encoder(result))
    )

  def logStepFailed(
      backend: EventStore,
      runId: RunId,
      step: Step[_],
      error: Throwable
  ): IO[EventId] = backend.unsafeRegisterEvent(
    runId,
    StepFailed(step.name, WorkflowError.fromThrowable(error))
  )

  def logStepCompensationStarted(
      backend: EventStore,
      runId: RunId,
      step: String
  ): IO[EventId] = backend.unsafeRegisterEvent(
    runId,
    StepCompensationStarted(step)
  )

  def logStepCompensationFailed(
      backend: EventStore,
      runId: RunId,
      step: String,
      error: Throwable
  ): IO[EventId] = backend.unsafeRegisterEvent(
    runId,
    StepCompensationFailed(
      step,
      WorkflowError.fromThrowable(error)
    )
  )

  def logStepCompensationCompleted(
      backend: EventStore,
      runId: RunId,
      step: String
  ): IO[EventId] = backend.unsafeRegisterEvent(
    runId,
    StepCompensationCompleted(
      step
    )
  )

  def logCompensationStarted[I](
      backend: EventStore,
      runId: RunId,
      expectedVersion: Version
  ): IO[Either[VersionConflict, EventId]] =
    backend.registerEvent(runId, CompensationStarted, expectedVersion)

  def logCompensationFailed(
      backend: EventStore,
      runId: RunId
  ): IO[EventId] = backend.unsafeRegisterEvent(
    runId,
    CompensationFailed
  )

  def logCompensationCompleted(
      backend: EventStore,
      runId: RunId
  ): IO[EventId] = backend.unsafeRegisterEvent(
    runId,
    CompensationCompleted
  )
}

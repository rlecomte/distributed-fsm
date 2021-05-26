package io.rlecomte.fsm.runtime

import io.rlecomte.fsm.store.EventStore
import cats.effect.IO
import io.circe.Encoder
import io.rlecomte.fsm._
import io.rlecomte.fsm.store.Version
import io.rlecomte.fsm.runtime.VersionConflict
import io.circe.Json
import io.rlecomte.fsm.Workflow.Step

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

  def logWorkflowResumed(
      backend: EventStore,
      runId: RunId,
      expectedVersion: Version
  ): IO[Either[VersionConflict, EventId]] =
    backend.registerEvent(runId, WorkflowResumed, expectedVersion)

  def logParStarted(
      backend: EventStore,
      runId: RunId,
      parentId: EventId,
      parNum: Int
  ): IO[EventId] = backend.unsafeRegisterEvent(runId, ParStarted(parentId, parNum))

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
      result: Json,
      parentId: EventId,
      parNum: Int
  ): IO[EventId] =
    backend.unsafeRegisterEvent(
      runId,
      StepCompleted(step.name, result, parentId, parNum)
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

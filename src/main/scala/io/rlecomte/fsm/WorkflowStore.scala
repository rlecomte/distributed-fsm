package io.rlecomte.fsm

import cats.effect.IO
import io.circe.Encoder
import cats.effect.kernel.Ref

trait BackendEventStore {
  def registerEvent(event: WorkflowEvent): IO[Unit]

  def readAllEvents: IO[List[WorkflowEvent]]

  def readEvents(runId: RunId): IO[List[WorkflowEvent]]
}

case class InMemoryBackendEventStore(ref: Ref[IO, Vector[WorkflowEvent]])
    extends BackendEventStore {
  override def registerEvent(event: WorkflowEvent): IO[Unit] =
    ref.getAndUpdate(vec => vec.appended(event)).void

  override def readAllEvents: IO[List[WorkflowEvent]] = ref.get.map(_.toList)

  override def readEvents(runId: RunId): IO[List[WorkflowEvent]] =
    readAllEvents.map(_.filter(_.id == runId))
}

object InMemoryBackendEventStore {
  val newStore: IO[InMemoryBackendEventStore] = for {
    refStore <- Ref.of[IO, Vector[WorkflowEvent]](Vector())
  } yield InMemoryBackendEventStore(refStore)
}

case class WorkflowLogger(backend: BackendEventStore) {
  def logWorkflowStarted(
      name: String,
      runId: RunId
  ): IO[WorkflowTracer] = for {
    _ <- backend.registerEvent(WorkflowStarted(name, runId))
    tracer = new WorkflowTracer(backend, runId)
  } yield tracer

  def logWorkflowCompleted(runId: RunId): IO[Unit] =
    backend.registerEvent(WorkflowCompleted(runId))

  def logWorkflowFailed(runId: RunId): IO[Unit] =
    backend.registerEvent(WorkflowFailed(runId))

  def logWorkflowExecution[A](
      workflowName: String,
      f: WorkflowTracer => IO[A]
  ): IO[A] = for { //TODO use bracket?
    runId <- RunId.newRunId
    tracer <- logWorkflowStarted(workflowName, runId)
    either <- f(tracer).attempt
    result <- either match {
      case Right(r)  => logWorkflowCompleted(runId).as(r)
      case Left(err) => logWorkflowFailed(runId) *> IO.raiseError(err)
    }
  } yield result
}

case class WorkflowTracer(backend: BackendEventStore, runId: RunId) {
  def logStepStarted(step: Workflow.Step[_]): IO[Unit] =
    backend.registerEvent(WorkflowStepStarted(step.name, runId))

  def logStepCompleted[A](
      step: Workflow.Step[A],
      result: A
  )(implicit encoder: Encoder[A]): IO[Unit] = backend.registerEvent(
    WorkflowStepCompleted(step.name, runId, encoder(result))
  )

  def logStepFailed(
      step: Workflow.Step[_],
      error: Throwable
  ): IO[Unit] =
    backend.registerEvent(
      WorkflowStepFailed(
        step.name,
        runId,
        WorkflowError.fromThrowable(error)
      )
    )

  def logStepCompensationStarted(step: Workflow.Step[_]): IO[Unit] =
    backend.registerEvent(WorkflowCompensationStarted(step.name, runId))

  def logStepCompensationFailed(
      step: Workflow.Step[_],
      error: Throwable
  ): IO[Unit] =
    backend.registerEvent(
      WorkflowCompensationFailed(
        step.name,
        runId,
        WorkflowError.fromThrowable(error)
      )
    )

  def logStepCompensationCompleted(
      step: Workflow.Step[_]
  ): IO[Unit] =
    backend.registerEvent(WorkflowCompensationCompleted(step.name, runId))

}

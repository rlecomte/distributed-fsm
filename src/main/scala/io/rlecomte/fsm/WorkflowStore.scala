package io.rlecomte.fsm

import cats.effect.IO
import cats.effect.concurrent.Ref
import io.circe.Encoder

trait WorkflowStore {
  def logWorkflowStarted(name: String, runId: RunId): IO[WorkflowTracer]

  def logWorkflowCompleted(runId: RunId): IO[Unit]

  def logWorkflowFailed(runId: RunId): IO[Unit]

  def logWorkflowExecution[A](
      workflowName: String,
      f: WorkflowTracer => IO[A]
  ): IO[A] = for {
    runId <- RunId.newRunId
    tracer <- logWorkflowStarted(workflowName, runId)
    either <- f(tracer).attempt
    result <- either match {
      case Right(r)  => logWorkflowCompleted(runId).as(r)
      case Left(err) => logWorkflowFailed(runId) *> IO.raiseError(err)
    }
  } yield result
}

trait WorkflowTracer {
  import Workflow._

  def logStepStarted(step: Step[_]): IO[Unit]

  def logStepCompleted[A: Encoder](step: Step[A], result: A): IO[Unit]

  def logStepFailed(step: Step[_], error: Throwable): IO[Unit]

  def logStepCompensationStarted(step: Step[_]): IO[Unit]

  def logStepCompensationFailed(step: Step[_], error: Throwable): IO[Unit]

  def logStepCompensationCompleted(step: Step[_]): IO[Unit]
}

case class InMemoryWorkflowStore(store: Ref[IO, Vector[WorkflowEvent]])
    extends WorkflowStore {

  case class InMemoryWorkflowTracer(runId: RunId) extends WorkflowTracer {
    override def logStepStarted(step: Workflow.Step[_]): IO[Unit] =
      store
        .getAndUpdate(v => v.appended(WorkflowStepStarted(step.name, runId)))
        .void

    override def logStepCompleted[A: Encoder](
        step: Workflow.Step[A],
        result: A
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(
            WorkflowStepCompleted(step.name, runId, Encoder[A].apply(result))
          )
        )
        .void

    override def logStepFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(
            WorkflowStepFailed(
              step.name,
              runId,
              WorkflowError.fromThrowable(error)
            )
          )
        )
        .void

    override def logStepCompensationStarted(step: Workflow.Step[_]): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationStarted(step.name, runId))
        )
        .void

    override def logStepCompensationFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(
            WorkflowCompensationFailed(
              step.name,
              runId,
              WorkflowError.fromThrowable(error)
            )
          )
        )
        .void

    override def logStepCompensationCompleted(
        step: Workflow.Step[_]
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationCompleted(step.name, runId))
        )
        .void

  }

  override def logWorkflowStarted(
      name: String,
      runId: RunId
  ): IO[WorkflowTracer] = for {
    _ <- store.getAndUpdate(v => v.appended(WorkflowStarted(name, runId)))
    tracer = new InMemoryWorkflowTracer(runId)
  } yield tracer

  override def logWorkflowCompleted(runId: RunId): IO[Unit] =
    store.getAndUpdate(v => v.appended(WorkflowCompleted(runId))).void

  override def logWorkflowFailed(runId: RunId): IO[Unit] =
    store.getAndUpdate(v => v.appended(WorkflowFailed(runId))).void
}

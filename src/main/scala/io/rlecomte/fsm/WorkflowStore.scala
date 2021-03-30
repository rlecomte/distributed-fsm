package io.rlecomte.fsm

import cats.effect.IO
import cats.Monad
import cats.free.Free
import cats.free.Free.liftF
import cats.effect.IOApp
import cats.effect.ExitCode
import cats.arrow.FunctionK
import cats.implicits._
import cats.effect.concurrent.Ref
import java.util.UUID
import java.{util => ju}
import cats.free.FreeApplicative
import cats.effect.ContextShift
import cats.Parallel
import cats.Applicative
import cats.~>

trait WorkflowStore {
  def logWorkflowStarted(name: String, runId: UUID): IO[WorkflowTracer]

  def logWorkflowCompleted(runId: UUID): IO[Unit]

  def logWorkflowFailed(runId: UUID): IO[Unit]

  def logWorkflowExecution[A](f: WorkflowTracer => IO[A]): IO[A] = for {
    runId <- IO(UUID.randomUUID())
    tracer <- logWorkflowStarted("foo", runId) //TODO name workflow
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

  def logStepCompleted[A](step: Step[A], result: A): IO[Unit]

  def logStepFailed(step: Step[_], error: Throwable): IO[Unit]

  def logStepCompensationStarted(step: Step[_]): IO[Unit]

  def logStepCompensationFailed(step: Step[_], error: Throwable): IO[Unit]

  def logStepCompensationCompleted(step: Step[_]): IO[Unit]
}

case class InMemoryWorkflowStore(store: Ref[IO, Vector[WorkflowEvent]])
    extends WorkflowStore {

  case class InMemoryWorkflowTracer(runId: UUID) extends WorkflowTracer {
    override def logStepStarted(step: Workflow.Step[_]): IO[Unit] =
      store
        .getAndUpdate(v => v.appended(WorkflowStepStarted(step.name, runId)))
        .as(())

    override def logStepCompleted[A](
        step: Workflow.Step[A],
        result: A
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowStepCompleted(step.name, runId, result.toString))
        )
        .as(())

    override def logStepFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowStepFailed(step.name, runId, error))
        )
        .as(())

    override def logStepCompensationStarted(step: Workflow.Step[_]): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationStarted(step.name, runId))
        )
        .as(())

    override def logStepCompensationFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationFailed(step.name, runId, error))
        )
        .as(())

    override def logStepCompensationCompleted(
        step: Workflow.Step[_]
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationCompleted(step.name, runId))
        )
        .as(())

  }

  override def logWorkflowStarted(
      name: String,
      runId: ju.UUID
  ): IO[WorkflowTracer] = for {
    _ <- store.getAndUpdate(v => v.appended(WorkflowStarted(name, runId)))
    tracer = new InMemoryWorkflowTracer(runId)
  } yield tracer

  override def logWorkflowCompleted(runId: ju.UUID): IO[Unit] =
    store.getAndUpdate(v => v.appended(WorkflowCompleted(runId))).as(())

  override def logWorkflowFailed(runId: ju.UUID): IO[Unit] =
    store.getAndUpdate(v => v.appended(WorkflowFailed(runId))).as(())
}

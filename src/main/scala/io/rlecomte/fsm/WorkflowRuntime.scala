package io.rlecomte.fsm

import cats.effect.IO
import cats.arrow.FunctionK
import cats.effect.Ref
import io.circe.Encoder
import cats.effect.kernel.Par
import cats.Parallel
import Workflow._

object WorkflowRuntime {
  type RollbackRef = Ref[IO, IO[Unit]]

  private class Run(
      runId: RunId,
      rollback: RollbackRef,
      backend: BackendEventStore
  ) {

    private def stackCompensation(
        step: Step[_]
    ): IO[Unit] = {

      val rollbackStep = for {
        _ <- logStepCompensationStarted(step)
        either <- step.compensate.attempt
        _ <- either match {
          case Right(_)  => logStepCompensationCompleted(step)
          case Left(err) => logStepCompensationFailed(step, err)
        }
      } yield ()

      rollback.modify(steps => (rollbackStep *> steps, ()))
    }

    private val runCompensation: IO[Unit] = rollback.get.flatten

    private val foldIO: FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step @ Step(_, _, _, _, encoder) =>
            processStep(step)(encoder)
          case FromSeq(seq) => seq.foldMap(foldIO)
          case FromPar(par) => {
            Par.ParallelF.value(
              par
                .foldMap(foldIO.andThen(toParallelIO))(
                  Parallel[IO, IO.Par].applicative
                )
            )
          }
        }
      }

    private val toParallelIO: FunctionK[IO, IO.Par] =
      new FunctionK[IO, IO.Par] {
        override def apply[A](fa: IO[A]): IO.Par[A] = Par.ParallelF(fa)
      }

    private def processStep[A](
        step: Step[A]
    )(implicit encoder: Encoder[A]): IO[A] = {
      logStepStarted(step) *> step.effect.attempt.flatMap {
        case Right(a) =>
          logStepCompleted(step, a) *> stackCompensation(step)
            .as(a)
        case Left(err) =>
          retryOrCompensate(step, err)
      }
    }

    private def retryOrCompensate[A](
        step: Step[A],
        err: Throwable
    )(implicit
        encoder: Encoder[A]
    ): IO[A] = {
      val retryIO = step.retryStrategy match {
        case NoRetry | LinearRetry(0) =>
          runCompensation *> IO
            .raiseError(
              err
            )
        case LinearRetry(nb) =>
          processStep(
            step.copy(retryStrategy = LinearRetry(nb - 1))
          )
      }

      logStepFailed(step, err) *> retryIO
    }

    def toIO[I, O](
        fsm: FSM[I, O],
        input: I
    ): IO[O] = for {
      _ <- logWorkflowStarted(fsm.name)
      either <- fsm.workflow(input).foldMap(foldIO).attempt
      result <- either match {
        case Right(r)  => logWorkflowCompleted.as(r)
        case Left(err) => logWorkflowFailed *> IO.raiseError(err)
      }
    } yield result

    def logWorkflowStarted(
        name: String
    ): IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowStarted(name))
      _ <- backend.registerEvent(evt)
    } yield evt.id

    val logWorkflowCompleted: IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowCompleted)
      _ <- backend.registerEvent(evt)
    } yield evt.id

    val logWorkflowFailed: IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowFailed)
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepStarted(step: Workflow.Step[_]): IO[EventId] =
      for {
        evt <- Event.newEvent(runId, WorkflowStepStarted(step.name))
        _ <- backend.registerEvent(evt)
      } yield evt.id

    def logStepCompleted[A](
        step: Workflow.Step[A],
        result: A
    )(implicit encoder: Encoder[A]): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        WorkflowStepCompleted(step.name, encoder(result))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        WorkflowStepFailed(step.name, WorkflowError.fromThrowable(error))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepCompensationStarted(
        step: Workflow.Step[_]
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        WorkflowCompensationStarted(step.name)
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepCompensationFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        WorkflowCompensationFailed(
          step.name,
          WorkflowError.fromThrowable(error)
        )
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepCompensationCompleted(
        step: Workflow.Step[_]
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        WorkflowCompensationCompleted(
          step.name
        )
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id
  }

  def compile[I, O](
      backend: BackendEventStore,
      workflow: FSM[I, O]
  ): CompiledFSM[I, O] = CompiledFSM { input =>
    for {
      runId <- RunId.newRunId
      ref <- Ref.of[IO, IO[Unit]](IO.unit)
      result <- new Run(runId, ref, backend).toIO(workflow, input)
    } yield result
  }
}

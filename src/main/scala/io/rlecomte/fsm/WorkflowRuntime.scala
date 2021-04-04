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

  private class Run(store: WorkflowLogger, rollback: RollbackRef) {

    private def stackCompensation(
        tracer: WorkflowTracer,
        step: Step[_]
    ): IO[Unit] = {

      val rollbackStep = for {
        _ <- tracer.logStepCompensationStarted(step)
        either <- step.compensate.attempt
        _ <- either match {
          case Right(_)  => tracer.logStepCompensationCompleted(step)
          case Left(err) => tracer.logStepCompensationFailed(step, err)
        }
      } yield ()

      rollback.modify(steps => (rollbackStep *> steps, ()))
    }

    private val runCompensation: IO[Unit] = rollback.get.flatten

    private def foldIO(
        tracer: WorkflowTracer
    ): FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step @ Step(_, _, _, _, encoder) =>
            processStep(tracer, step)(encoder)
          case FromSeq(seq) => seq.foldMap(foldIO(tracer))
          case FromPar(par) => {
            Par.ParallelF.value(
              par
                .foldMap(foldIO(tracer).andThen(toParallelIO))(
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
        tracer: WorkflowTracer,
        step: Step[A]
    )(implicit encoder: Encoder[A]): IO[A] = {
      tracer.logStepStarted(step) *> step.effect.attempt.flatMap {
        case Right(a) =>
          tracer.logStepCompleted(step, a) *> stackCompensation(tracer, step)
            .as(a)
        case Left(err) =>
          retryOrCompensate(tracer, step, err)
      }
    }

    private def retryOrCompensate[A](
        tracer: WorkflowTracer,
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
            tracer,
            step.copy(retryStrategy = LinearRetry(nb - 1))
          )
      }

      tracer.logStepFailed(step, err) *> retryIO
    }

    def toIO[I, O](
        fsm: FSM[I, O],
        input: I
    ): IO[O] =
      store.logWorkflowExecution(
        fsm.name,
        tracer => fsm.workflow(input).foldMap(foldIO(tracer))
      )
  }

  def compile[I, O](
      logger: WorkflowLogger,
      workflow: FSM[I, O]
  ): CompiledFSM[I, O] = CompiledFSM { input =>
    for {
      ref <- Ref.of[IO, IO[Unit]](IO.unit)
      result <- new Run(logger, ref).toIO(workflow, input)
    } yield result
  }
}

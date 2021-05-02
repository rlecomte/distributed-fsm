package io.rlecomte.fsm.runtime

import cats.effect.IO
import cats.arrow.FunctionK
import io.circe.Encoder
import cats.effect.kernel.Par
import cats.Parallel
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm._
import io.rlecomte.fsm.store.EventStore

class WorkflowIO(runId: RunId, backend: EventStore) {

  def foldIO(parentId: EventId): FunctionK[WorkflowOp, IO] =
    new FunctionK[WorkflowOp, IO] {
      override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
        case step @ Step(_, _, _, _, encoder, _) =>
          processStep(step, parentId)(encoder)

        case AlreadyProcessedStep(_, result, _) =>
          IO.pure(result)

        case FromSeq(seq) => {
          for {
            parentId <- EventLogger.logSeqStarted(backend, runId, parentId)
            result <- seq.foldMap(foldIO(parentId))
          } yield result
        }
        case FromPar(par) => {
          def subGraph(parentId: EventId) = Par.ParallelF.value(
            par
              .foldMap(parFoldIO(parentId))(
                Parallel[IO, IO.Par].applicative
              )
          )

          for {
            parentId <- EventLogger.logParStarted(backend, runId, parentId)
            result <- subGraph(parentId)
          } yield result
        }
      }
    }

  private def parFoldIO(parentId: EventId): FunctionK[WorkflowOp, IO.Par] =
    new FunctionK[WorkflowOp, IO.Par] {
      override def apply[A](op: WorkflowOp[A]): IO.Par[A] = {
        val subProcess = for {
          result <- foldIO(parentId)(op)
        } yield result

        Par.ParallelF(subProcess.uncancelable)
      }
    }

  private def processStep[A](
      step: Step[A],
      parentId: EventId
  )(implicit encoder: Encoder[A]): IO[A] = {
    EventLogger.logStepStarted(backend, runId, step, parentId) *> step.effect.attempt.flatMap {
      case Right(a) =>
        EventLogger.logStepCompleted(backend, runId, step, a).as(a)
      case Left(err) =>
        retry(step, parentId, err)
    }
  }

  private def retry[A](
      step: Step[A],
      parentId: EventId,
      err: Throwable
  )(implicit
      encoder: Encoder[A]
  ): IO[A] = {
    val retryIO = step.retryStrategy match {
      case NoRetry | LinearRetry(0) =>
        IO.raiseError(err)
      case LinearRetry(nb) =>
        processStep(
          step.copy(retryStrategy = LinearRetry(nb - 1)),
          parentId
        )
    }

    EventLogger.logStepFailed(backend, runId, step, err) *> retryIO
  }
}

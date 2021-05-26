package io.rlecomte.fsm.runtime

import cats.effect.IO
import cats.arrow.FunctionK
import cats.effect.kernel.Par
import cats.Parallel
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm._
import io.rlecomte.fsm.store.EventStore

class WorkflowIO(runId: RunId, backend: EventStore) {

  def foldIO(parentId: EventId, parNum: Int): FunctionK[WorkflowOp, IO] =
    new FunctionK[WorkflowOp, IO] {
      override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
        case step @ Step(_, _, _, _, _) =>
          processStep(step, parentId, parNum)

        case FromPar(par) => {
          def subGraph(parentId: EventId) = {
            Par.ParallelF.value(
              par
                .foldMap {
                  parFoldIO(parentId)
                }(Parallel[IO, IO.Par].applicative)
            )
          }

          for {
            parentId <- EventLogger.logParStarted(backend, runId, parentId, parNum)
            result <- subGraph(parentId)
          } yield result
        }
      }
    }

  private def parFoldIO(parentId: EventId): FunctionK[IndexedWorkflow, IO.Par] =
    new FunctionK[IndexedWorkflow, IO.Par] {
      override def apply[A](w: IndexedWorkflow[A]): IO.Par[A] =
        Par.ParallelF(w.workflow.foldMap(foldIO(parentId, w.parNum)))
    }

  private def processStep[A](
      step: Step[A],
      parentId: EventId,
      parNum: Int
  ): IO[A] = {
    EventLogger.logStepStarted(backend, runId, step, parentId) *> step.effect.attempt.flatMap {
      case Right((ja, a)) =>
        EventLogger.logStepCompleted(backend, runId, step, ja, parentId, parNum).as(a)
      case Left(err) =>
        retry(step, parentId, parNum, err)
    }
  }

  private def retry[A](
      step: Step[A],
      parentId: EventId,
      parNum: Int,
      err: Throwable
  ): IO[A] = {
    val retryIO = step.retryStrategy match {
      case NoRetry | LinearRetry(0) =>
        IO.raiseError(err)
      case LinearRetry(nb) =>
        processStep(
          step.copy(retryStrategy = LinearRetry(nb - 1)),
          parentId,
          parNum
        )
    }

    EventLogger.logStepFailed(backend, runId, step, err) *> retryIO
  }
}

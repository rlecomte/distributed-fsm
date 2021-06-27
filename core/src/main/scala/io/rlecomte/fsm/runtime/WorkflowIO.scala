package io.rlecomte.fsm.runtime

import cats.Applicative
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.data.Validated
import cats.effect.IO
import cats.effect.implicits._
import cats.effect.kernel.Par
import cats.implicits._
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm._
import io.rlecomte.fsm.store.EventStore

class WorkflowIO(runId: RunId, backend: EventStore) {

  type Eff[A] = EitherT[IO, Unit, A]
  type ParEff[A] = IO.Par[Validated[Unit, A]]

  val parEffApp = Applicative[IO.Par].compose(Applicative[Validated[Unit, *]])

  def foldIO(parentId: EventId, parNum: Int): FunctionK[WorkflowOp, Eff] =
    new FunctionK[WorkflowOp, Eff] {
      override def apply[A](op: WorkflowOp[A]): Eff[A] = op match {
        case step @ Step(_, _, _, _, _) =>
          EitherT.liftF(processStep(step, parentId, parNum))

        case AsyncStep(_, token, _) =>
          EitherT[IO, Unit, A](
            EventLogger.logStepSuspended(backend, runId, token).map(_ => Left[Unit, A](()))
          )

        case FromPar(par) => {
          def subGraph(parentId: EventId) = {
            Par.ParallelF
              .value(
                par
                  .foldMap {
                    parFoldIO(parentId)
                  }(parEffApp)
              )
              .map(_.toEither)
          }

          for {
            parentId <- EitherT.liftF(EventLogger.logParStarted(backend, runId, parentId, parNum))
            result <- EitherT(subGraph(parentId))
          } yield result
        }
      }
    }

  private def parFoldIO(parentId: EventId): FunctionK[IndexedWorkflow, ParEff] =
    new FunctionK[IndexedWorkflow, ParEff] {
      override def apply[A](w: IndexedWorkflow[A]): ParEff[A] = {
        val subProcess = w.workflow.foldMap(foldIO(parentId, w.parNum))
        Par.ParallelF(subProcess.value.map(_.toValidated))
      }
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

package io.rlecomte.fsm.runtime

import cats.effect.IO
import cats.arrow.FunctionK
import io.circe.Encoder
import cats.effect.kernel.Par
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm._
import io.rlecomte.fsm.store.EventStore
import cats.data.EitherT
import cats.data.Validated
import cats.Applicative
import cats.effect.implicits._
import cats.implicits._

class WorkflowIO(runId: RunId, backend: EventStore) {

  type Eff[A] = EitherT[IO, Unit, A]
  type ParEff[A] = IO.Par[Validated[Unit, A]]

  val parEffApp = Applicative[IO.Par].compose(Applicative[Validated[Unit, *]])

  def foldIO(parentId: EventId): FunctionK[WorkflowOp, Eff] =
    new FunctionK[WorkflowOp, Eff] {
      override def apply[A](op: WorkflowOp[A]): Eff[A] = op match {
        case step @ Step(_, _, _, _, encoder, _) =>
          EitherT.liftF[IO, Unit, A](processStep(step, parentId)(encoder))

        case AlreadyProcessedStep(_, result, _) =>
          EitherT.pure[IO, Unit](result)

        case FromSeq(seq) => {
          for {
            parentId <- EitherT.liftF[IO, Unit, EventId](
              EventLogger.logSeqStarted(backend, runId, parentId)
            )
            result <- seq.foldMap(foldIO(parentId))
          } yield result
        }

        case FromPar(par) => {
          def subGraph(parentId: EventId) = EitherT(
            Par.ParallelF
              .value(
                par
                  .foldMap(parFoldIO(parentId))(parEffApp)
              )
              .map(_.toEither)
          )

          for {
            parentId <- EitherT.liftF[IO, Unit, EventId](
              EventLogger.logParStarted(backend, runId, parentId)
            )
            result <- subGraph(parentId)
          } yield result
        }
      }
    }

  private def parFoldIO(parentId: EventId): FunctionK[WorkflowOp, ParEff] =
    new FunctionK[WorkflowOp, ParEff] {
      override def apply[A](op: WorkflowOp[A]): ParEff[A] = {
        val subProcess = foldIO(parentId)(op)
        Par.ParallelF(subProcess.value.map(_.toValidated).uncancelable)
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

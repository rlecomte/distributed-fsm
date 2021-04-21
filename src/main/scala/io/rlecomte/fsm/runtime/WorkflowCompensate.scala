package io.rlecomte.fsm.runtime

import io.rlecomte.fsm.Workflow._
import cats.data.{EitherT, State}
import cats.data.Validated
import cats.data.Nested
import cats.effect.IO
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.store.EventStore
import cats.arrow.FunctionK
import cats.implicits._

object WorkflowCompensate {
  type AccState[A] = State[IO[Unit], A]
  type Acc[A] = EitherT[AccState, Unit, A]
  type ParAcc[A] = Nested[AccState, Validated[Unit, *], A]

  def compensate(backend: EventStore, runId: RunId, workflow: Workflow[_]): IO[Unit] = {
    workflow
      .compile(accCompensation(runId, backend))
      .runTailRec
      .value
      .runEmptyS
      .value
  }

  def accCompensation(runId: RunId, backend: EventStore): FunctionK[WorkflowOp, Acc] =
    new FunctionK[WorkflowOp, Acc] {
      override def apply[A](fa: WorkflowOp[A]): Acc[A] = fa match {
        case AlreadyProcessedStep(step, r, c) =>
          EitherT.liftF(State(acc => (compensateStep(step, c) *> acc, r)))

        case FromSeq(op) => op.foldMap(accCompensation(runId, backend))

        case FromPar(op) =>
          EitherT(
            op.foldMap[ParAcc](accCompensation(runId, backend).andThen(parStoreCompensation))
              .value
              .map(_.toEither)
          )

        case _ => EitherT.fromEither[AccState](Left(()))
      }

      def compensateStep(step: String, compensation: IO[Unit]): IO[Unit] = {
        for {
          _ <- EventLogger.logStepCompensationStarted(backend, runId, step)
          r <- compensation.attempt
          rr <- r match {
            case Right(v) => EventLogger.logStepCompensationCompleted(backend, runId, step).as(v)
            case Left(err) =>
              EventLogger.logStepCompensationFailed(backend, runId, step, err) *> IO.raiseError(err)
          }
        } yield rr
      }

    }

  val parStoreCompensation: FunctionK[Acc, ParAcc] = new FunctionK[Acc, ParAcc] {
    def apply[A](fa: Acc[A]): ParAcc[A] = Nested(fa.value.map(_.toValidated))
  }
}

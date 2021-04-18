package io.rlecomte.fsm

import io.circe.Json
import cats.arrow.FunctionK
import io.rlecomte.fsm.Workflow.WorkflowOp
import io.rlecomte.fsm.Workflow.Step
import io.rlecomte.fsm.Workflow.AlreadyProcessedStep
import io.rlecomte.fsm.Workflow.FromSeq
import io.rlecomte.fsm.Workflow.FromPar
import cats.data.{EitherT, State}
import cats.data.Validated
import cats.data.Nested
import cats.implicits._
import cats.effect.IO
import io.rlecomte.fsm.store.EventStore

object WorkflowResume {
  type AccState[A] = State[IO[Unit], A]
  type Acc[A] = EitherT[AccState, Unit, A]
  type AccValidated[A] = Validated[Unit, A]
  type ParAcc[A] = Nested[AccState, AccValidated, A]

  case class RunHistory[I](
      runId: RunId,
      input: I,
      alreadyProcessedStep: Map[String, Json]
  )

  def resume[I, O](
      backend: EventStore,
      fsm: FSM[I, O],
      history: RunHistory[I]
  ): ResumedFSM[I, O] = {
    val hydratedWorkflow = fsm.workflow(history.input).compile(resumeWorkflow(history))
    val compensation = hydratedWorkflow
      .compile(storeCompensation(history.runId, backend))
      .runTailRec
      .value
      .runEmptyS
      .value
    ResumedFSM(
      run = WorkflowRuntime.resumeWorkflow(backend, history.runId, fsm.name, hydratedWorkflow),
      compensate = compensation
    )
  }

  def resumeWorkflow(history: RunHistory[_]): FunctionK[WorkflowOp, WorkflowOp] =
    new FunctionK[WorkflowOp, WorkflowOp] {
      override def apply[A](fa: Workflow.WorkflowOp[A]): Workflow.WorkflowOp[A] = fa match {
        case step @ Step(name, _, compensate, _, _, decoder) =>
          history.alreadyProcessedStep
            .get(name)
            .flatMap(input => decoder.decodeJson(input).toOption)
            .map(result => AlreadyProcessedStep(name, result, compensate))
            .getOrElse(step)

        case FromSeq(op) => FromSeq(op.compile(resumeWorkflow(history)))

        case FromPar(op) => FromPar(op.compile(resumeWorkflow(history)))

        case other => other
      }
    }

  def storeCompensation(runId: RunId, backend: EventStore): FunctionK[WorkflowOp, Acc] =
    new FunctionK[WorkflowOp, Acc] {
      override def apply[A](fa: WorkflowOp[A]): Acc[A] = fa match {
        case AlreadyProcessedStep(step, r, c) =>
          EitherT.liftF(State(acc => (compensateStep(step, c) *> acc, r)))

        case FromSeq(op) => op.foldMap(storeCompensation(runId, backend))

        case FromPar(op) =>
          EitherT(
            op.foldMap[ParAcc](storeCompensation(runId, backend).andThen(parStoreCompensation))
              .value
              .map(_.toEither)
          )

        case _ => EitherT.fromEither[AccState](Left(()))
      }

      def compensateStep(step: String, compensation: IO[Unit]): IO[Unit] = {
        for {
          _ <- logStepCompensationStarted(runId, step)
          r <- compensation.attempt
          rr <- r match {
            case Right(v)  => logStepCompensationCompleted(runId, step).as(v)
            case Left(err) => logStepCompensationFailed(runId, step, err) *> IO.raiseError(err)
          }
        } yield rr
      }

      def logStepCompensationStarted(
          runId: RunId,
          step: String
      ): IO[EventId] = for {
        evt <- Event.newEvent(
          runId,
          StepCompensationStarted(step)
        )
        _ <- backend.registerEvent(evt)
      } yield evt.id

      def logStepCompensationFailed(
          runId: RunId,
          step: String,
          error: Throwable
      ): IO[EventId] = for {
        evt <- Event.newEvent(
          runId,
          StepCompensationFailed(
            step,
            WorkflowError.fromThrowable(error)
          )
        )
        _ <- backend.registerEvent(evt)
      } yield evt.id

      def logStepCompensationCompleted(
          runId: RunId,
          step: String
      ): IO[EventId] = for {
        evt <- Event.newEvent(
          runId,
          StepCompensationCompleted(
            step
          )
        )
        _ <- backend.registerEvent(evt)
      } yield evt.id
    }

  val parStoreCompensation: FunctionK[Acc, ParAcc] = new FunctionK[Acc, ParAcc] {
    def apply[A](fa: Acc[A]): ParAcc[A] = Nested(fa.value.map(_.toValidated))
  }
}

package io.rlecomte.fsm.runtime

import cats.effect.FiberIO
import cats.effect.IO
import cats.implicits._
import io.circe.Encoder
import io.circe.Json
import io.rlecomte.fsm.EventId
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm.store._

import WorkflowResume._

sealed trait StateError extends Exception
case class CantDecodePayload(err: String) extends StateError
case object CantResumeState extends StateError
case class IncoherentState(error: String) extends StateError
case object CantCompensateState extends StateError
case object NoResult extends StateError
case class VersionConflict(expected: Version, current: Version) extends StateError

case class WorkflowFiber[O](runId: RunId, fiber: FiberIO[Either[Unit, O]]) {

  val join: IO[WorkflowOutcome[O]] =
    fiber.join.flatMap { outcome =>
      outcome.fold(
        IO.pure(ProcessCancelled),
        err => IO.pure(ProcessFailed(err)),
        eff =>
          eff.map {
            case Left(_)  => ProcessSuspended
            case Right(v) => ProcessSucceeded(v)
          }
      )
    }
}
sealed trait WorkflowOutcome[+O] {

  def fold[B](canceled: => B, errored: Throwable => B, suspended: => B, completed: O => B): B =
    this match {
      case ProcessCancelled    => canceled
      case ProcessFailed(e)    => errored(e)
      case ProcessSuspended    => suspended
      case ProcessSucceeded(v) => completed(v)
    }
}
case object ProcessCancelled extends WorkflowOutcome[Nothing]
case class ProcessFailed[O](err: Throwable) extends WorkflowOutcome[O]
case object ProcessSuspended extends WorkflowOutcome[Nothing]
case class ProcessSucceeded[O](value: O) extends WorkflowOutcome[O]

object WorkflowRuntime {

  def start[I, O](store: EventStore, fsm: FSM[I, O], input: I): IO[WorkflowFiber[O]] = {
    for {
      runId <- RunId.newRunId
      r <- launch(store, runId, fsm.name, input, fsm.workflow(input), Version.empty)(
        fsm.inputCodec
      )
        .flatMap(IO.fromEither)
    } yield r
  }

  def resume[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  ): IO[Either[StateError, WorkflowFiber[O]]] = {
    WorkflowResume.resumeRun(store, runId, fsm)(fsm.inputCodec).flatMap {
      case Left(err) =>
        IO.pure(Left(err))
      case Right(ResumeRunPayload(version, workflow)) =>
        launchResume(store, runId, workflow, version)
    }
  }

  def compensate[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  ): IO[Either[StateError, WorkflowFiber[Unit]]] = {
    WorkflowResume.compensate(store, runId, fsm)(fsm.inputCodec).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(CompensateRunPayload(version, steps)) =>
        launchCompensation[I, O](store, runId, steps, version)
    }
  }

  private def launchCompensation[I, O](
      store: EventStore,
      runId: RunId,
      compensations: List[Compensation],
      version: Version
  ): IO[Either[StateError, WorkflowFiber[Unit]]] =
    EventLogger.logCompensationStarted(store, runId, version).flatMap {
      case Right(_) =>
        compensations
          .foldMap(s => compensateStep(store, runId, s.stepName, s.eff))
          .attempt
          .flatMap {
            case Right(_) =>
              EventLogger.logCompensationCompleted(store, runId).as(Either.right[Unit, Unit](()))
            case Left(e) => EventLogger.logCompensationFailed(store, runId) *> IO.raiseError(e)
          }
          .start
          .map(fib => Right(WorkflowFiber(runId, fib)))

      case Left(err) => IO.pure(Left(err))
    }

  private def compensateStep(
      backend: EventStore,
      runId: RunId,
      step: String,
      compensation: IO[Unit]
  ): IO[Unit] = {
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

  private def launch[I, O](
      store: EventStore,
      runId: RunId,
      name: String,
      input: I,
      workflow: Workflow[O],
      version: Version
  )(implicit encoder: Encoder[I]): IO[Either[StateError, WorkflowFiber[O]]] =
    EventLogger.logWorkflowStarted(store, name, runId, input, version)(encoder).flatMap {
      case Right(parentId) => startWorkflow(store, runId, parentId, workflow).map(Right(_))

      case Left(err) => IO.pure(Left(err))
    }

  private def launchResume[O](
      store: EventStore,
      runId: RunId,
      workflow: Workflow[O],
      version: Version
  ): IO[Either[StateError, WorkflowFiber[O]]] =
    EventLogger.logWorkflowResumed(store, runId, version).flatMap {
      case Right(parentId) => startWorkflow(store, runId, parentId, workflow).map(Right(_))

      case Left(err) => IO.pure(Left(err))
    }

  private def startWorkflow[O](
      store: EventStore,
      runId: RunId,
      parentId: EventId,
      workflow: Workflow[O]
  ): IO[WorkflowFiber[O]] = {
    val runner = new WorkflowIO(runId, store)

    workflow
      .foldMap(runner.foldIO(parentId, 0))
      .value
      .attempt
      .flatMap {
        case Right(Right(v)) => EventLogger.logWorkflowCompleted(store, runId).as(Right(v))
        case Right(Left(_))  => EventLogger.logWorkflowSuspended(store, runId).as(Left(()))
        case Left(e)         => EventLogger.logWorkflowFailed(store, runId) *> IO.raiseError(e)
      }
      .start
      .map(fib => WorkflowFiber(runId, fib))
  }

  def feed(store: EventStore, runId: RunId, token: String, payload: Json): IO[Unit] = {
    EventLogger.logWorkflowFeeded(store, runId, token, payload).void
  }

  def feedAndResume[I, O](
      store: EventStore,
      runId: RunId,
      token: String,
      payload: Json,
      fsm: FSM[I, O]
  ): IO[Either[StateError, WorkflowFiber[O]]] = {
    feed(store, runId, token, payload).flatMap(_ => resume(store, fsm, runId))
  }
}

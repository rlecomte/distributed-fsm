package io.rlecomte.fsm.runtime

import cats.effect.FiberIO
import cats.effect.IO
import cats.implicits._
import io.circe.Decoder
import io.circe.Encoder
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

sealed trait WorkflowOutcome[O] {
  val runId: RunId
}
case class ProcessCancelled[O](runId: RunId) extends WorkflowOutcome[O]
case class ProccessFailed[O](runId: RunId, err: Throwable) extends WorkflowOutcome[O]
case class ProcessSucceeded[O](runId: RunId, value: O) extends WorkflowOutcome[O]

object WorkflowRuntime {

  def start[I, O](store: EventStore, fsm: FSM[I, O], input: I)(implicit
      encoder: Encoder[I]
  ): IO[(RunId, FiberIO[O])] = {
    for {
      runId <- RunId.newRunId
      r <- launch(store, runId, fsm.name, input, fsm.workflow(input), Version.empty)
        .flatMap(IO.fromEither)
    } yield (runId, r)
  }

  def startSync[I, O](store: EventStore, fsm: FSM[I, O], input: I)(implicit
      encoder: Encoder[I]
  ): IO[WorkflowOutcome[O]] = {
    start(store, fsm, input)(encoder).flatMap { case (runId, fib) =>
      fib.join.flatMap { outcome =>
        outcome.fold(
          IO.pure(ProcessCancelled(runId)),
          err => IO.pure(ProccessFailed(runId, err)),
          eff => eff.map(ProcessSucceeded(runId, _))
        )
      }
    }
  }

  def resume[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit
      decoder: Decoder[I]
  ): IO[Either[StateError, FiberIO[O]]] = {
    WorkflowResume.resumeRun(store, runId, fsm).flatMap {
      case Left(err) =>
        IO.pure(Left(err))
      case Right(ResumeRunPayload(version, workflow)) =>
        launchResume(store, runId, workflow, version)
    }
  }

  def resumeSync[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit
      decoder: Decoder[I]
  ): IO[Either[StateError, WorkflowOutcome[O]]] = resume(store, fsm, runId).flatMap {
    case Right(fib) =>
      fib.join.flatMap { outcome =>
        outcome.fold(
          IO.pure(Right(ProcessCancelled(runId))),
          err => IO.pure(Right(ProccessFailed(runId, err))),
          eff => eff.map(v => Right(ProcessSucceeded(runId, v)))
        )
      }

    case Left(err) => IO.pure(Left(err))
  }

  def compensate[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit decoder: Decoder[I]): IO[Either[StateError, Unit]] = {
    WorkflowResume.compensate(store, runId, fsm).flatMap {
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
  ): IO[Either[StateError, Unit]] =
    EventLogger.logCompensationStarted(store, runId, version).flatMap {
      case Right(_) =>
        compensations
          .foldMap(s => compensateStep(store, runId, s.stepName, s.eff))
          .attempt
          .flatMap {
            case Right(_) => EventLogger.logCompensationCompleted(store, runId).void
            case Left(e)  => EventLogger.logCompensationFailed(store, runId) *> IO.raiseError(e)
          }
          .map(Right(_))

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
  )(implicit encoder: Encoder[I]): IO[Either[StateError, FiberIO[O]]] =
    EventLogger.logWorkflowStarted(store, name, runId, input, version)(encoder).flatMap {
      case Right(parentId) => startWorkflow(store, runId, parentId, workflow)

      case Left(err) => IO.pure(Left(err))
    }

  private def launchResume[O](
      store: EventStore,
      runId: RunId,
      workflow: Workflow[O],
      version: Version
  ): IO[Either[StateError, FiberIO[O]]] =
    EventLogger.logWorkflowResumed(store, runId, version).flatMap {
      case Right(parentId) => startWorkflow(store, runId, parentId, workflow)

      case Left(err) => IO.pure(Left(err))
    }

  private def startWorkflow[O](
      store: EventStore,
      runId: RunId,
      parentId: EventId,
      workflow: Workflow[O]
  ): IO[Either[StateError, FiberIO[O]]] = {
    val runner = new WorkflowIO(runId, store)

    workflow
      .foldMap(runner.foldIO(parentId, 0))
      .attempt
      .flatMap {
        case Right(a) => EventLogger.logWorkflowCompleted(store, runId).as(a)
        case Left(e)  => EventLogger.logWorkflowFailed(store, runId) *> IO.raiseError(e)
      }
      .start
      .map(Right(_))
  }

  //def feed(fsm: FSM[_, _], token: SuspendToken, payload: Json): IO[Unit] = ???
}

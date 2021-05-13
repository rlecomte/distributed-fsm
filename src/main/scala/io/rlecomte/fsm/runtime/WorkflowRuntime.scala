package io.rlecomte.fsm.runtime

import io.rlecomte.fsm.FSM
import cats.effect.{IO, FiberIO}
import io.rlecomte.fsm.RunId
import io.circe.Encoder
import io.circe.Decoder
import io.rlecomte.fsm.store._
import io.rlecomte.fsm.Workflow._

sealed trait StateError extends Exception
case class CantDecodePayload(err: String) extends StateError
case object CantResumeState extends StateError
case object CantCompensateState extends StateError
case object NoResult extends StateError
case class VersionConflict(expected: Version, current: Version) extends StateError

object WorkflowRuntime {

  def start[I, O](store: EventStore, fsm: FSM[I, O], input: I)(implicit
      encoder: Encoder[I]
  ): IO[(RunId, FiberIO[Option[O]])] = {
    for {
      runId <- RunId.newRunId
      r <- launch(store, runId, fsm.name, input, fsm.workflow(input), Version.empty)
        .flatMap(IO.fromEither)
    } yield (runId, r)
  }

  def startSync[I, O](store: EventStore, fsm: FSM[I, O], input: I)(implicit
      encoder: Encoder[I]
  ): IO[(RunId, Option[O])] = {
    start(store, fsm, input)(encoder).flatMap { case (runId, fib) =>
      fib.join.flatMap {
        _.fold(
          IO.raiseError(new RuntimeException("Cancelled")),
          IO.raiseError,
          _.map((runId, _))
        )
      }
    }
  }

  def resume[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit
      decoder: Decoder[I],
      encoder: Encoder[I]
  ): IO[Either[StateError, FiberIO[Option[O]]]] = {
    WorkflowResume.resume(runId, store, fsm).flatMap {
      case Left(err) =>
        IO.pure(Left(err))
      case Right(WorkflowResume(version, input, workflow)) =>
        launch(store, runId, fsm.name, input, workflow, version)
    }
  }

  def resumeSync[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit
      decoder: Decoder[I],
      encoder: Encoder[I]
  ): IO[Either[StateError, Option[O]]] = resume(store, fsm, runId).flatMap {
    case Right(fib) =>
      fib.join.flatMap { outcome =>
        outcome.fold(
          IO.raiseError(new RuntimeException("Cancelled")),
          IO.raiseError,
          _.map(Right(_))
        )
      }

    case Left(err) => IO.pure(Left(err))
  }

  def compensate[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit decoder: Decoder[I]): IO[Either[StateError, Unit]] = {
    WorkflowResume.resume(runId, store, fsm).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(WorkflowResume(version, _, workflow)) =>
        launchCompensation[I, O](store, runId, workflow, version)
    }
  }

  private def launchCompensation[I, O](
      store: EventStore,
      runId: RunId,
      workflow: Workflow[O],
      version: Version
  ): IO[Either[StateError, Unit]] =
    EventLogger.logCompensationStarted(store, runId, version).flatMap {
      case Right(_) =>
        WorkflowCompensate
          .compensate(store, runId, workflow)
          .attempt
          .flatMap {
            case Right(_) => EventLogger.logCompensationCompleted(store, runId).void
            case Left(e)  => EventLogger.logCompensationFailed(store, runId) *> IO.raiseError(e)
          }
          .map(Right(_))

      case Left(err) => IO.pure(Left(err))
    }

  private def launch[I, O](
      store: EventStore,
      runId: RunId,
      name: String,
      input: I,
      workflow: Workflow[O],
      version: Version
  )(implicit encoder: Encoder[I]): IO[Either[StateError, FiberIO[Option[O]]]] =
    EventLogger.logWorkflowStarted(store, name, runId, input, version)(encoder).flatMap {

      case Right(parentId) =>
        val runner = new WorkflowIO(runId, store)

        workflow
          .foldMap(runner.foldIO(parentId))
          .value
          .map(_.toOption)
          .attempt
          .flatMap {
            case Right(Some(a)) => EventLogger.logWorkflowCompleted(store, runId).as(Some(a))
            case Right(None)    => EventLogger.logWorkflowSuspended(store, runId).as(None)
            case Left(e)        => EventLogger.logWorkflowFailed(store, runId) *> IO.raiseError(e)
          }
          .start
          .map(Right(_))

      case Left(err) => IO.pure(Left(err))
    }

  //def result[I, O](fsm: FSM[I, O], runId: RunId): IO[Either[StateError, O]] = ???

  //def feed(fsm: FSM[_, _], token: SuspendToken, payload: Json): IO[Unit] = ???
}

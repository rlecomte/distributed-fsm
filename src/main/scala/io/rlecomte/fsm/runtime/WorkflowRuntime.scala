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

object WorkflowRuntime {

  def start[I, O](store: EventStore, fsm: FSM[I, O], input: I)(implicit
      encoder: Encoder[I]
  ): IO[(RunId, FiberIO[O])] = {
    for {
      runId <- RunId.newRunId
      r <- launch(store, runId, fsm.name, input, fsm.workflow(input), EmptyVersion)
    } yield r
  }

  def resume[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit
      decoder: Decoder[I],
      encoder: Encoder[I]
  ): IO[Either[StateError, (RunId, FiberIO[O])]] = {
    WorkflowResume.resume(runId, store, fsm).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(WorkflowResume(version, input, workflow)) =>
        launch(store, runId, fsm.name, input, workflow, version).map(Right(_))
    }
  }

  def compensate[I, O](
      store: EventStore,
      fsm: FSM[I, O],
      runId: RunId
  )(implicit decoder: Decoder[I]): IO[Either[StateError, Unit]] = {
    WorkflowResume.resume(runId, store, fsm).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(WorkflowResume(version, _, workflow)) =>
        launchCompensation[I, O](store, runId, workflow, version).map(Right(_))
    }
  }

  private def launchCompensation[I, O](
      store: EventStore,
      runId: RunId,
      workflow: Workflow[O],
      version: Version
  ): IO[Unit] = for {
    parentIdEither <- EventLogger.logCompensationStarted(store, runId, version)
    _ <- IO.fromEither(parentIdEither)
    _ <- WorkflowCompensate
      .compensate(store, runId, workflow)
      .attempt
      .flatMap {
        case Right(_) => EventLogger.logCompensationCompleted(store, runId).void
        case Left(e)  => EventLogger.logCompensationFailed(store, runId) *> IO.raiseError(e)
      }
  } yield ()

  private def launch[I, O](
      store: EventStore,
      runId: RunId,
      name: String,
      input: I,
      workflow: Workflow[O],
      version: Version
  )(implicit encoder: Encoder[I]): IO[(RunId, FiberIO[O])] = for {
    parentIdEither <- EventLogger.logWorkflowStarted(store, name, runId, input, version)(encoder)
    parentId <- IO.fromEither(parentIdEither)
    runner = new WorkflowIO(runId, store)
    fiber <- workflow
      .foldMap(runner.foldIO(parentId))
      .attempt
      .flatMap {
        case Right(a) => EventLogger.logWorkflowCompleted(store, runId).as(a)
        case Left(e)  => EventLogger.logWorkflowFailed(store, runId) *> IO.raiseError(e)
      }
      .start
  } yield (runId, fiber)

  //def result[I, O](fsm: FSM[I, O], runId: RunId): IO[Either[StateError, O]] = ???

  //def feed(fsm: FSM[_, _], token: SuspendToken, payload: Json): IO[Unit] = ???
}

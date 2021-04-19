package io.rlecomte.fsm

import cats.effect.IO
import cats.arrow.FunctionK
import cats.effect.kernel.Par
import Workflow._
import cats.implicits._
import io.rlecomte.fsm.store.EventStore
import cats.data.EitherT
import cats.data.Nested
import cats.data.Validated
import cats.Applicative

object WorkflowRuntime {

  type Eff[A] = EitherT[IO, Unit, A]
  type V[A] = Validated[Unit, A]
  type ParEff[A] = Nested[IO.Par, V, A]

  import cats.effect.implicits._
  val parEffApplicative: Applicative[ParEff] = Nested.catsDataApplicativeForNested[IO.Par, V]

  private class Run(
      runId: RunId,
      backend: EventStore
  ) {

    private def foldIO(parentId: EventId): FunctionK[WorkflowOp, Eff] =
      new FunctionK[WorkflowOp, Eff] {
        override def apply[A](op: WorkflowOp[A]): Eff[A] = op match {
          case step @ Step(_, _, _, _, _, _) =>
            processStep(step, parentId)

          case AlreadyProcessedStep(_, result, _) =>
            EitherT.pure(result)

          case step @ Suspend(_, _, _, _, _, _) =>
            processSuspendFirstStep(step, parentId)

          case step @ PendingSuspend(_, _, _, _) =>
            processSuspendSecondStep(step)

          case FromSeq(seq) => {
            for {
              parentId <- EitherT.liftF(logSeqStarted(parentId))
              result <- seq.foldMap(foldIO(parentId))
            } yield result
          }
          case FromPar(par) => {
            def subGraph(parentId: EventId) = Par.ParallelF
              .value(
                par
                  .foldMap(parFoldIO(parentId))(parEffApplicative)
                  .value
              )
              .map(_.toEither)

            for {
              parentId <- EitherT.liftF(logParStarted(parentId))
              result <- EitherT(subGraph(parentId))
            } yield result
          }
        }
      }

    private def parFoldIO(parentId: EventId): FunctionK[WorkflowOp, ParEff] =
      new FunctionK[WorkflowOp, ParEff] {
        override def apply[A](op: WorkflowOp[A]): ParEff[A] = {
          val subProcess = for {
            result <- foldIO(parentId)(op)
          } yield result

          Nested(Par.ParallelF(subProcess.value.map(_.toValidated).uncancelable))
        }
      }

    private def processStep[A](
        step: Step[A],
        parentId: EventId
    ): Eff[A] = {
      EitherT.liftF(logStepStarted(step, parentId) *> step.effect.attempt).flatMap {
        case Right(a) =>
          EitherT.liftF[IO, Unit, A](logStepCompleted(step, a).as(a))
        case Left(err) =>
          retry(step, parentId, err)
      }
    }

    private def processSuspendFirstStep[A](step: Suspend[_, A], parentId: EventId): Eff[A] = {
      for {
        _ <- EitherT.liftF(logSuspendFirstStepStarted(step, parentId))
        v <- EitherT.liftF(SuspendToken.newToken.flatMap(t => step.first(t)).attempt)
        r <- v match {
          case Right(_) =>
            EitherT(logSuspendFirstStepCompleted(step).as(Either.left[Unit, A](())))
          case Left(err) =>
            EitherT.liftF[IO, Unit, A](
              logSuspendFirstStepFailed(step, err) *> IO.raiseError(err)
            )
        }
      } yield r
    }

    private def processSuspendSecondStep[A](step: PendingSuspend[A]): Eff[A] = {
      for {
        _ <- EitherT.liftF(logSuspendSecondStepStarted(step))
        v <- EitherT.liftF(step.second.attempt)
        r <- v match {
          case Right(a) =>
            EitherT.liftF[IO, Unit, A](logSuspendSecondStepCompleted(step, a).as(a))
          case Left(err) =>
            EitherT.liftF[IO, Unit, A](
              logSuspendSecondStepFailed(step, err) *> IO.raiseError(err)
            )
        }
      } yield r
    }

    private def retry[A](
        step: Step[A],
        parentId: EventId,
        err: Throwable
    ): Eff[A] = {
      val retryIO: Eff[A] = step.retryStrategy match {
        case NoRetry | LinearRetry(0) =>
          EitherT.liftF(IO.raiseError(err))
        case LinearRetry(nb) =>
          processStep(
            step.copy(retryStrategy = LinearRetry(nb - 1)),
            parentId
          )
      }

      EitherT.liftF(logStepFailed(step, err)).flatMap(_ => retryIO)
    }

    def toIO[O](
        workflowName: String,
        workflow: Workflow[O]
    ): IO[WorkflowResult[O]] = for {
      parentId <- logWorkflowStarted(workflowName)
      either <- workflow.foldMap(foldIO(parentId)).value.attempt
      result <- either match {
        case Right(Left(_))  => logWorkflowCompleted.as(WorkflowSuspended)
        case Right(Right(r)) => logWorkflowCompleted.as(WorkflowDone(r))
        case Left(err)       => logWorkflowFailed.as(WorkflowFailure(err))
      }
    } yield result

    def logWorkflowStarted(
        name: String
    ): IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowStarted(name))
      _ <- backend.registerEvent(evt)
    } yield evt.id

    val logWorkflowCompleted: IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowCompleted)
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSeqStarted(parentId: EventId): IO[EventId] = for {
      evt <- Event.newEvent(runId, SeqStarted(parentId))
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logParStarted(
        parentId: EventId
    ): IO[EventId] = for {
      evt <- Event.newEvent(runId, ParStarted(parentId))
      _ <- backend.registerEvent(evt)
    } yield evt.id

    val logWorkflowFailed: IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowFailed)
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepStarted(
        step: Workflow.Step[_],
        parentId: EventId
    ): IO[EventId] =
      for {
        evt <- Event.newEvent(
          runId,
          StepStarted(step.name, parentId)
        )
        _ <- backend.registerEvent(evt)
      } yield evt.id

    def logStepCompleted[A](
        step: Workflow.Step[A],
        result: A
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        StepCompleted(step.name, step.circeEncoder(result))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        StepFailed(step.name, WorkflowError.fromThrowable(error))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSuspendFirstStepStarted(
        step: Suspend[_, _],
        parentId: EventId
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        SuspendFirstStepStarted(step.name, parentId)
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSuspendFirstStepFailed(
        step: Suspend[_, _],
        error: Throwable
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        SuspendFirstStepFailed(step.name, WorkflowError.fromThrowable(error))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSuspendFirstStepCompleted(
        step: Suspend[_, _]
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        SuspendFirstStepCompleted(step.name)
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSuspendSecondStepStarted(
        step: PendingSuspend[_]
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        SuspendSecondStepStarted(step.name)
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSuspendSecondStepFailed(
        step: PendingSuspend[_],
        error: Throwable
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        SuspendSecondStepFailed(step.name, WorkflowError.fromThrowable(error))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logSuspendSecondStepCompleted[A](
        step: PendingSuspend[A],
        result: A
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        SuspendSecondStepCompleted(step.name, step.encoder(result))
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id
  }

  def compile[I, O](
      backend: EventStore,
      fsm: FSM[I, O]
  ): CompiledFSM[I, O] = CompiledFSM { input =>
    for {
      runId <- RunId.newRunId
      fiber <- new Run(runId, backend).toIO(fsm.name, fsm.workflow(input)).start
    } yield (runId, fiber)
  }

  def resumeWorkflow[O](
      backend: EventStore,
      runId: RunId,
      fsmName: String,
      workflow: Workflow[O]
  ): IO[WorkflowResult[O]] = new Run(runId, backend).toIO(fsmName, workflow)
}

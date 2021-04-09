package io.rlecomte.fsm

import cats.effect.IO
import cats.arrow.FunctionK
import cats.effect.Ref
import io.circe.Encoder
import cats.effect.kernel.Par
import Workflow._
import cats.Parallel
import cats.implicits._

object WorkflowRuntime {
  case class Ctx(
      linkedEventId: Option[EventId] = None,
      rollback: IO[Unit] = IO.unit,
      subs: List[StateRef] = Nil
  )

  type StateRef = Ref[IO, Ctx]

  private class Run(
      runId: RunId,
      backend: BackendEventStore
  ) {

    private def stackCompensation(
        state: StateRef,
        step: Step[_]
    ): IO[Unit] = {

      val rollbackStep = for {
        _ <- logStepCompensationStarted(step)
        either <- step.compensate.attempt
        _ <- either match {
          case Right(_)  => logStepCompensationCompleted(step)
          case Left(err) => logStepCompensationFailed(step, err)
        }
      } yield ()

      state.update(ctx => ctx.copy(rollback = rollbackStep *> ctx.rollback))
    }

    private def runCompensation(state: StateRef): IO[Unit] =
      state.get.flatMap(_.rollback)

    private def foldIO(state: StateRef): FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step @ Step(_, _, _, _, encoder) =>
            processStep(step, state)(encoder)
          case FromSeq(seq) => seq.foldMap(foldIO(state))
          case FromPar(par) => {
            val subGraph = Par.ParallelF.value(
              par
                .foldMap(parFoldIO(state))(
                  Parallel[IO, IO.Par].applicative
                )
            )

            for {
              result <- subGraph
              ctx <- state.get
              subRollback <- ctx.subs.traverse(_.get).map(_.foldMap(_.rollback))
              _ <- state.update(ctx => ctx.copy(rollback = subRollback *> ctx.rollback))
            } yield result
          }
        }
      }

    private def parFoldIO(current: StateRef): FunctionK[WorkflowOp, IO.Par] =
      new FunctionK[WorkflowOp, IO.Par] {
        override def apply[A](op: WorkflowOp[A]): IO.Par[A] = Par.ParallelF(for {
          state <- current.get.flatMap(ctx =>
            Ref.of[IO, Ctx](ctx.copy(rollback = IO.unit, subs = Nil))
          )
          _ <- logParStepStarted(state)
          _ <- current.update(ctx => ctx.copy(subs = state :: ctx.subs))
          result <- foldIO(state)(op)
          _ <- logParStepCompleted(state)
        } yield result)
      }

    private def processStep[A](
        step: Step[A],
        state: StateRef
    )(implicit encoder: Encoder[A]): IO[A] = {
      logStepStarted(step, state) *> step.effect.attempt.flatMap {
        case Right(a) =>
          logStepCompleted(step, a) *> stackCompensation(state, step)
            .as(a)
        case Left(err) =>
          retry(step, state, err)
      }
    }

    private def retry[A](
        step: Step[A],
        state: StateRef,
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
            state
          )
      }

      logStepFailed(step, err) *> retryIO
    }

    def toIO[I, O](
        fsm: FSM[I, O],
        state: StateRef,
        input: I
    ): IO[O] = for {
      _ <- logWorkflowStarted(fsm.name, state)
      either <- fsm.workflow(input).foldMap(foldIO(state)).attempt
      result <- either match {
        case Right(r)  => logWorkflowCompleted.as(r)
        case Left(err) => runCompensation(state) *> logWorkflowFailed *> IO.raiseError(err)
      }
    } yield result

    def logWorkflowStarted(
        name: String,
        state: StateRef
    ): IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowStarted(name))
      _ <- backend.registerEvent(evt)
      _ <- state.update(ctx => ctx.copy(linkedEventId = Some(evt.id)))
    } yield evt.id

    val logWorkflowCompleted: IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowCompleted)
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logParStepStarted(
        state: StateRef
    ): IO[EventId] = for {
      evt <- Event.newEvent(runId, ParStepStarted)
      _ <- backend.registerEvent(evt)
      _ <- state.update(ctx => ctx.copy(linkedEventId = Some(evt.id)))
    } yield evt.id

    def logParStepCompleted(state: StateRef): IO[EventId] =
      for {
        linkedEventId <- state.get.map(_.linkedEventId)
        evt <- Event.newEvent(
          runId,
          ParStepCompleted(linkedEventId.get) //FIXME Option.get
        )
        _ <- backend.registerEvent(evt)
      } yield evt.id

    val logWorkflowFailed: IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowFailed)
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepStarted(
        step: Workflow.Step[_],
        state: StateRef
    ): IO[EventId] =
      for {
        linkedEventId <- state.get.map(_.linkedEventId)
        evt <- Event.newEvent(
          runId,
          StepStarted(step.name, linkedEventId.get) //FIXME Option.get
        )
        _ <- backend.registerEvent(evt)
      } yield evt.id

    def logStepCompleted[A](
        step: Workflow.Step[A],
        result: A
    )(implicit encoder: Encoder[A]): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        StepCompleted(step.name, encoder(result))
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

    def logStepCompensationStarted(
        step: Workflow.Step[_]
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        StepCompensationStarted(step.name)
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepCompensationFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        StepCompensationFailed(
          step.name,
          WorkflowError.fromThrowable(error)
        )
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id

    def logStepCompensationCompleted(
        step: Workflow.Step[_]
    ): IO[EventId] = for {
      evt <- Event.newEvent(
        runId,
        StepCompensationCompleted(
          step.name
        )
      )
      _ <- backend.registerEvent(evt)
    } yield evt.id
  }

  def compile[I, O](
      backend: BackendEventStore,
      workflow: FSM[I, O]
  ): CompiledFSM[I, O] = CompiledFSM { input =>
    for {
      runId <- RunId.newRunId
      state <- Ref.of[IO, Ctx](Ctx())
      result <- new Run(runId, backend).toIO(workflow, state, input)
    } yield result
  }
}

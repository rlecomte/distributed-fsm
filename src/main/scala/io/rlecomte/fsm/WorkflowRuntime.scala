package io.rlecomte.fsm

import cats.Applicative
import cats.arrow.FunctionK
import cats.data.Validated.{Invalid, Valid}
import cats.data.{Nested, ValidatedNel}
import cats.effect.{IO, Ref}
import cats.effect.implicits.commutativeApplicativeForParallelF
import cats.effect.kernel.Par
import cats.implicits._
import io.circe.{Decoder, Encoder, Json}
import io.rlecomte.fsm.Workflow._
import io.circe.syntax._

object WorkflowRuntime {
  case class Ctx(
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
          case Right(_) => logStepCompensationCompleted(step)
          case Left(err) => logStepCompensationFailed(step, err)
        }
      } yield ()

      state.update(ctx => ctx.copy(rollback = rollbackStep *> ctx.rollback))
    }

    private def runCompensation(state: StateRef): IO[Unit] =
      state.get.flatMap(_.rollback)

    private def foldIO(parentId: EventId, state: StateRef): FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step@Step(_, _, _, _, encoder, _) =>
            processStep(step, parentId, state)(encoder)
          case FromSeq(seq) => {
            for {
              parentId <- logSeqStarted(parentId)
              result <- seq.foldMap(foldIO(parentId, state))
            } yield result
          }
          case AlreadyProcessedStep(name, value) =>
            IO.println(s"Getting value from already processed step $name") *> IO.pure(value)
          case FromPar(par) => {
            def subGraph(parentId: EventId) = Par.ParallelF.value(
              par
                .foldMap(parFoldIO(parentId, state))(
                  Applicative[Nested[IO.Par, ValidatedNel[Throwable, *], *]]
                  //                  Parallel[IO, IO.Par].applicative.compose(Applicative[ValidatedNel[Throwable, *]])
                ).value)

            for {
              parentId <- logParStarted(parentId)
              result <- subGraph(parentId)
              ctx <- state.get
              subRollback <- ctx.subs.traverse(_.get).map(_.foldMap(_.rollback))
              _ <- state.update(ctx => ctx.copy(rollback = subRollback *> ctx.rollback, subs = Nil))
              realResult <- result match {
                case Valid(v) => IO.pure(v)
                case Invalid(e) => IO.raiseError(e.head) // TODO find a way to merge errors
              }
            } yield realResult
          }
        }
      }

    type IOParValidated[A] = Nested[IO.Par, ValidatedNel[Throwable, *], A]

    private def parFoldIO(parentId: EventId, current: StateRef): FunctionK[WorkflowOp, IOParValidated] =
      new FunctionK[WorkflowOp, IOParValidated] {
        override def apply[A](op: WorkflowOp[A]): IOParValidated[A] = Nested {
          Par.ParallelF(for {
            state <- Ref.of[IO, Ctx](Ctx(rollback = IO.unit, subs = Nil))
            _ <- current.update(ctx => ctx.copy(subs = state :: ctx.subs))
            result <- foldIO(parentId, state)(op).attempt.map(e => e.toValidatedNel)
          } yield result)
        }
      }

    private def processStep[A](
                                step: Step[A],
                                parentId: EventId,
                                state: StateRef
                              )(implicit encoder: Encoder[A]): IO[A] = {
      logStepStarted(step, parentId) *> step.effect.attempt.flatMap {
        case Right(a) =>
          logStepCompleted(step, a) *> stackCompensation(state, step)
            .as(a)
        case Left(err) =>
          retry(step, parentId, state, err)
      }
    }

    private def retry[A](
                          step: Step[A],
                          parentId: EventId,
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
            parentId,
            state
          )
      }

      logStepFailed(step, err) *> retryIO
    }

    def toIO[I, O](
                    fsm: FSM[I, O],
                    state: StateRef,
                    input: I
                  )(implicit encoder:Encoder[I]): IO[O] = for {
      parentId <- logWorkflowStarted(fsm.name, input.asJson(encoder))
      either <- fsm.workflow(input).foldMap(foldIO(parentId, state)).attempt
      result <- either match {
        case Right(r) => logWorkflowCompleted.as(r)
        case Left(err) => runCompensation(state) *> logWorkflowFailed *> IO.raiseError(err)
      }
    } yield result

    def logWorkflowStarted(
                            name: String,
                            payload: Json
                          ): IO[EventId] = for {
      evt <- Event.newEvent(runId, WorkflowStarted(name, payload))
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
                   )(implicit encoder: Encoder[I]): CompiledFSM[I, O] = CompiledFSM { input =>
    for {
      runId <- RunId.newRunId
      state <- Ref.of[IO, Ctx](Ctx())
      result <- new Run(runId, backend).toIO(workflow, state, input)(encoder).attempt
    } yield (runId, result)
  }

  def retryCompile[I, O](
                       backend: BackendEventStore,
                       workflow: FSM[I, O],
                       input: I
                     )(implicit encoder: Encoder[I]): RetryFSM[I, O] = RetryFSM(
    for {
      runId <- RunId.newRunId
      state <- Ref.of[IO, Ctx](Ctx())
      result <- new Run(runId, backend).toIO(workflow, state, input)(encoder).attempt
    } yield (runId, result)
  )

  def retryFromState[I, O](
                            runId: RunId,
                            backend: BackendEventStore,
                            fsm: FSM[I, O]
                          )(implicit encoder: Encoder[I], decoder: Decoder[I]): IO[RetryFSM[I, O]] = for {
    events <- backend.readEvents(runId)
    stepCompletedEvents = events
        .map(e => e.payload)
        .collect {
          case StepCompleted(step, payload) => (step, payload)
        }.toMap
    input = events.map(e => e.payload)
      .collectFirst { case WorkflowStarted(_, input) => input}
      .flatMap(json => json.as[I](decoder).toOption)
      .get // TODO remove this unsafe get
    transformedFSM = fsm.copy(workflow = fsm.workflow.andThen(w => w.mapK(new FunctionK[WorkflowOp, WorkflowOp]() {
      override def apply[A](fa: WorkflowOp[A]): WorkflowOp[A] = fa match {
        case s@Step(name, _, _, _, _, circeDecoder) => {
          stepCompletedEvents.get(name)
            .flatMap(p => p.as[A](circeDecoder).toOption)
            .map(p => AlreadyProcessedStep(name, p))
            .getOrElse(s)
        }
        case _ => fa
      }
    })))
  } yield retryCompile[I, O](backend, transformedFSM, input)(encoder)

}


case class WorkflowRunException(val runId: RunId, val cause: Throwable)
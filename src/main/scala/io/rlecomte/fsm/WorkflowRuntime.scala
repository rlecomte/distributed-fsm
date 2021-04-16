package io.rlecomte.fsm

import cats.effect.IO
import cats.arrow.FunctionK
import io.circe.Encoder
import cats.effect.kernel.Par
import Workflow._
import cats.Parallel

object WorkflowRuntime {
  private class Run(
      runId: RunId,
      backend: BackendEventStore
  ) {

    private def foldIO(parentId: EventId): FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step @ Step(_, _, _, _, encoder, _) =>
            processStep(step, parentId)(encoder)

          case AlreadyProcessedStep(_, result, _) =>
            IO.pure(result)

          case FromSeq(seq) => {
            for {
              parentId <- logSeqStarted(parentId)
              result <- seq.foldMap(foldIO(parentId))
            } yield result
          }
          case FromPar(par) => {
            def subGraph(parentId: EventId) = Par.ParallelF.value(
              par
                .foldMap(parFoldIO(parentId))(
                  Parallel[IO, IO.Par].applicative
                )
            )

            for {
              parentId <- logParStarted(parentId)
              result <- subGraph(parentId)
            } yield result
          }
        }
      }

    private def parFoldIO(parentId: EventId): FunctionK[WorkflowOp, IO.Par] =
      new FunctionK[WorkflowOp, IO.Par] {
        override def apply[A](op: WorkflowOp[A]): IO.Par[A] = {
          val subProcess = for {
            result <- foldIO(parentId)(op)
          } yield result

          Par.ParallelF(subProcess.uncancelable)
        }
      }

    private def processStep[A](
        step: Step[A],
        parentId: EventId
    )(implicit encoder: Encoder[A]): IO[A] = {
      logStepStarted(step, parentId) *> step.effect.attempt.flatMap {
        case Right(a) =>
          logStepCompleted(step, a).as(a)
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

      logStepFailed(step, err) *> retryIO
    }

    def toIO[O](
        workflowName: String,
        workflow: Workflow[O]
    ): IO[O] = for {
      parentId <- logWorkflowStarted(workflowName)
      either <- workflow.foldMap(foldIO(parentId)).attempt
      result <- either match {
        case Right(r)  => logWorkflowCompleted.as(r)
        case Left(err) => logWorkflowFailed *> IO.raiseError(err)
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

  }

  def compile[I, O](
      backend: BackendEventStore,
      fsm: FSM[I, O]
  ): CompiledFSM[I, O] = CompiledFSM { input =>
    for {
      runId <- RunId.newRunId
      result <- new Run(runId, backend).toIO(fsm.name, fsm.workflow(input)).attempt
    } yield (runId, result)
  }

  def resumeWorkflow[O](
      backend: BackendEventStore,
      runId: RunId,
      fsmName: String,
      workflow: Workflow[O]
  ): IO[O] = {
    for {
      result <- new Run(runId, backend).toIO(fsmName, workflow)
    } yield result
  }
}

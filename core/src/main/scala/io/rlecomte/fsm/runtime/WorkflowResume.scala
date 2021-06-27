package io.rlecomte.fsm.runtime

import cats.effect.IO
import cats.free.Free
import cats.implicits._
import io.circe.Decoder
import io.circe.Json
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm._
import io.rlecomte.fsm.store.EventStore
import io.rlecomte.fsm.store.Version

object WorkflowResume {

  final case class Compensation(stepName: String, eff: IO[Unit])
  final case class ResumeRunPayload[I, O](version: Version, workflow: Workflow[O])
  final case class CompensateRunPayload(version: Version, step: List[Compensation])

  sealed trait ResumeState[A]
  object ResumeState {
    case class Init[A]() extends ResumeState[A]
    case class Started[A](
        paths: Map[EventId, EventId],
        workflow: Free[ResumeOp, A],
        executedSteps: List[Compensation],
        feeded: Map[String, Json]
    ) extends ResumeState[A]
    case class Suspended[A](
        paths: Map[EventId, EventId],
        workflow: Free[ResumeOp, A],
        executedSteps: List[Compensation],
        feeded: Map[String, Json]
    ) extends ResumeState[A]
    case class Completed[A](
        value: A,
        executedSteps: List[Compensation]
    ) extends ResumeState[A]
    case class Failed[A](
        paths: Map[EventId, EventId],
        workflow: Free[ResumeOp, A],
        executedSteps: List[Compensation],
        feeded: Map[String, Json]
    ) extends ResumeState[A]
    case class CompensationStarted[A](executedSteps: List[Step[_]]) extends ResumeState[A]
    case class CompensationFailed[A](executedSteps: List[Step[_]]) extends ResumeState[A]
    case class CompensationCompleted[A]() extends ResumeState[A]

    def init[A]: ResumeState[A] = Init()
  }
  import ResumeState._

  def resumeRun[I: Decoder, O](
      backend: EventStore,
      runId: RunId,
      fsm: FSM[I, O]
  ): IO[Either[StateError, ResumeRunPayload[I, O]]] = {
    loadState(backend, runId, fsm).map(_.flatMap {
      case (Failed(_, workflow, _, _), version) =>
        val resumedWorkflow = workflow.compile(ResumeOp.fromResumeOp)
        Right(ResumeRunPayload(version, resumedWorkflow))
      case (Suspended(_, workflow, _, _), version) =>
        val resumedWorkflow = workflow.compile(ResumeOp.fromResumeOp)
        Right(ResumeRunPayload(version, resumedWorkflow))
      case _ => Left(CantResumeState)
    })
  }

  def compensate[I: Decoder, O](
      backend: EventStore,
      runId: RunId,
      fsm: FSM[I, O]
  ): IO[Either[StateError, CompensateRunPayload]] = {
    loadState(backend, runId, fsm).map(_.flatMap {
      case (Failed(_, _, steps, _), version) =>
        Right(CompensateRunPayload(version, steps))
      case (Suspended(_, _, steps, _), version) =>
        Right(CompensateRunPayload(version, steps))
      case (Completed(_, steps), version) =>
        Right(CompensateRunPayload(version, steps))
      case _ => Left(CantResumeState)
    })
  }

  def loadState[I: Decoder, O](
      store: EventStore,
      runId: RunId,
      fsm: FSM[I, O]
  ): IO[Either[StateError, (ResumeState[O], Version)]] = {
    store.readEvents(runId).map { events =>
      events
        .foldM((ResumeState.init[O], Version.empty)) { case ((s, _), e) =>
          dispatcher[I, O](fsm)(s, e).map(s => (s, e.seqNum))
        }
    }
  }

  def dispatcher[I, O](fsm: FSM[I, O])(
      state: ResumeState[O],
      event: Event
  )(implicit decoder: Decoder[I]): Either[StateError, ResumeState[O]] =
    state match {
      case Init()                    => init(fsm, event)
      case s @ Started(_, _, _, _)   => started(s, event)
      case s @ Suspended(_, _, _, _) => suspended(s, event)
      case Completed(_, _)           => completed(event)
      case s @ Failed(_, _, _, _)    => failed(s, event)
      case CompensationStarted(_)    => compensationStarted(event)
      case CompensationFailed(_)     => compensationFailed(event)
      case CompensationCompleted()   => compensationCompleted(event)
    }

  def init[I, O](fsm: FSM[I, O], event: Event)(implicit
      decoder: Decoder[I]
  ): Either[StateError, ResumeState[O]] = {
    event.payload match {
      case WorkflowStarted(_, input) =>
        for {
          i <- decoder.decodeJson(input).leftMap(e => CantDecodePayload(e.message))
          w = fsm.workflow(i).compile(ResumeOp.toResumeOp)
        } yield Started(Map.empty, w, Nil, Map.empty)
      case evt => Left(IncoherentState(s"Oops : $evt"))
    }
  }

  def started[A](state: Started[A], event: Event): Either[StateError, ResumeState[A]] = {
    event.payload match {
      case p @ ParStarted(correlationId, _) =>
        state.workflow.resume match {
          case Left(op) =>
            val updatedOp =
              ResumeOp.parStarted(event.id, 1, p, traceIds(state.paths, correlationId))(op)
            val updatedPaths = state.paths + ((correlationId, event.id))
            Right(state.copy(updatedPaths, Free.roll(updatedOp)))
          case Right(_) =>
            Left(IncoherentState("workflow completed but diverge from event source."))
        }
      case StepStarted(_, _) => Right(state)
      case StepFailed(_, _)  => Right(state)
      case p @ StepCompleted(_, payload, correlationId, _) =>
        state.workflow.resume match {
          case Left(op) =>
            val result =
              ResumeOp
                .completedStep(0, p, traceIds(state.paths, correlationId), op)
                .map(_.flatten)
                .run(None)

            result match {
              case Right((Some(step), updatedWorkflow)) =>
                Right(
                  state.copy(
                    executedSteps =
                      Compensation(step.name, step.compensate(payload)) :: state.executedSteps,
                    workflow = updatedWorkflow
                  )
                )

              case Right((None, _)) =>
                Left(IncoherentState(s"A completed step doesn't exist : $p"))

              case Left(err) => Left(err)
            }

          case Right(_) =>
            Left(IncoherentState("workflow completed but diverge from event source."))
        }

      case AsyncStepFeeded(token, payload) =>
        val feededUpdated = state.feeded + ((token, payload))
        state.workflow.resume match {
          case Left(op) =>
            ResumeOp.feed(feededUpdated, op).map { updatedWorkflow =>
              state.copy(feeded = feededUpdated, workflow = updatedWorkflow.flatten)
            }
          case Right(_) => //continue
            Right(state.copy(feeded = feededUpdated))
        }

      case AsyncStepSuspended(_) =>
        state.workflow.resume match {
          case Left(op) =>
            ResumeOp.feed(state.feeded, op).map { updatedWorkflow =>
              state.copy(workflow = updatedWorkflow.flatten)
            }
          case Right(_) =>
            Left(IncoherentState("workflow is completed but event source is suspended."))
        }

      case WorkflowSuspended =>
        Right(Suspended(state.paths, state.workflow, state.executedSteps, state.feeded))

      case WorkflowCompleted =>
        state.workflow.resume match {
          case Left(_)  => Left(IncoherentState("workflow isn't completed but event source is."))
          case Right(v) => Right(Completed(v, state.executedSteps))
        }

      case WorkflowFailed =>
        Right(Failed(state.paths, state.workflow, state.executedSteps, state.feeded))

      case evt => Left(IncoherentState(s"Oops : $evt"))
    }
  }

  def suspended[A](state: Suspended[A], event: Event): Either[StateError, ResumeState[A]] = {
    event.payload match {
      case AsyncStepFeeded(token, payload) =>
        val feededUpdated = state.feeded + ((token, payload))
        state.workflow.resume match {
          case Left(op) =>
            ResumeOp.feed(feededUpdated, op).map { updatedWorkflow =>
              state.copy(feeded = feededUpdated, workflow = updatedWorkflow.flatten)
            }
          case Right(_) => //continue
            Right(state.copy(feeded = feededUpdated))
        }

      case io.rlecomte.fsm.CompensationStarted => Right(CompensationStarted(Nil))

      case io.rlecomte.fsm.WorkflowResumed =>
        Right(Started(state.paths, state.workflow, state.executedSteps, state.feeded))

      case evt => Left(IncoherentState(s"Oops : $evt"))
    }
  }

  def completed[A](event: Event): Either[StateError, ResumeState[A]] = {
    event.payload match {
      case io.rlecomte.fsm.CompensationStarted => Right(CompensationStarted(Nil))
      case evt                                 => Left(IncoherentState(s"Oops : $evt"))
    }
  }

  def failed[A](state: Failed[A], event: Event): Either[StateError, ResumeState[A]] =
    event.payload match {
      case AsyncStepFeeded(token, payload) =>
        val feededUpdated = state.feeded + ((token, payload))
        state.workflow.resume match {
          case Left(op) =>
            ResumeOp.feed(feededUpdated, op).map { updatedWorkflow =>
              state.copy(feeded = feededUpdated, workflow = updatedWorkflow.flatten)
            }
          case Right(_) => //continue
            Right(state.copy(feeded = feededUpdated))
        }
      case io.rlecomte.fsm.CompensationStarted => Right(CompensationStarted(Nil))
      case io.rlecomte.fsm.WorkflowResumed =>
        Right(Started(state.paths, state.workflow, state.executedSteps, state.feeded))
      case evt => Left(IncoherentState(s"Oops : $evt"))
    }

  def compensationStarted[A](
      event: Event
  ): Either[StateError, ResumeState[A]] =
    event.payload match {
      case StepCompensationStarted(_)            => Right(CompensationStarted(Nil))
      case StepCompensationFailed(_, _)          => Right(CompensationStarted(Nil))
      case StepCompensationCompleted(_)          => Right(CompensationStarted(Nil))
      case io.rlecomte.fsm.CompensationCompleted => Right(CompensationCompleted())
      case io.rlecomte.fsm.CompensationFailed    => Right(CompensationFailed(Nil))
      case evt                                   => Left(IncoherentState(s"Oops : $evt"))
    }

  def compensationFailed[A](
      event: Event
  ): Either[StateError, ResumeState[A]] =
    event.payload match {
      case io.rlecomte.fsm.CompensationStarted => Right(CompensationStarted(Nil))
      case evt                                 => Left(IncoherentState(s"Oops : $evt"))
    }

  def compensationCompleted[A](
      event: Event
  ): Either[StateError, ResumeState[A]] =
    event.payload match {
      case evt => Left(IncoherentState(s"Oops : $evt"))
    }

  def traceIds(refs: Map[EventId, EventId], id: EventId): List[EventId] = {
    def f(i: EventId): List[EventId] = {
      refs
        .get(i)
        .map(parentId => id :: f(parentId))
        .getOrElse(Nil)
    }

    f(id).reverse
  }
}

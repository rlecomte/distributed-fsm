package io.rlecomte.fsm.runtime

import io.rlecomte.fsm.EventId
import cats.implicits._
import cats.free.Free
import cats.~>
import cats.Functor
import io.rlecomte.fsm.Workflow.WorkflowOp
import cats.free.FreeApplicative
import io.rlecomte.fsm.Workflow.Step
import io.rlecomte.fsm.Workflow.FromPar
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.ParStarted
import io.rlecomte.fsm.StepCompleted
import io.rlecomte.fsm.WorkflowStarted
import io.rlecomte.fsm.FSM
import io.circe.Decoder
import io.rlecomte.fsm.StepStarted
import io.rlecomte.fsm.StepFailed
import io.rlecomte.fsm.WorkflowCompleted
import io.rlecomte.fsm.WorkflowFailed
import io.rlecomte.fsm.StepCompensationStarted
import io.rlecomte.fsm.StepCompensationFailed
import io.rlecomte.fsm.StepCompensationCompleted
import cats.data.StateT
import cats.Applicative
import io.rlecomte.fsm.Workflow._
import cats.effect.IO
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.store.EventStore
import io.rlecomte.fsm.store.Version

case class IndexedResume[A](parNum: Int, workflow: Free[ResumeOp, A])
sealed trait ResumeOp[A]
case class ResumeStep[A](sub: Step[A]) extends ResumeOp[A]
case class RunningPar[A](id: EventId, sub: FreeApplicative[IndexedResume, A]) extends ResumeOp[A]
case class WaitingPar[A](sub: FreeApplicative[IndexedResume, A]) extends ResumeOp[A]

object ResumeOp {

  implicit val functorResumeOp: Functor[ResumeOp] = new Functor[ResumeOp] {
    def map[A, B](fa: ResumeOp[A])(f: A => B): ResumeOp[B] = fa match {
      case ResumeStep(Step(n, e, c, r, d)) =>
        ResumeStep(Step(n, e.map { case (j, p) => (j, f(p)) }, c, r, d.map(f)))
      case RunningPar(id, sub) => RunningPar(id, sub.map(f))
      case WaitingPar(sub)     => WaitingPar(sub.map(f))
    }
  }

  val toResumeOp: WorkflowOp ~> ResumeOp = λ[WorkflowOp ~> ResumeOp] {
    case FromPar(pstep) =>
      val pstepz =
        pstep.compile(
          λ[IndexedWorkflow ~> IndexedResume](iw =>
            IndexedResume(iw.parNum, iw.workflow.compile(toResumeOp))
          )
        )
      WaitingPar(pstepz)

    case s @ Step(_, _, _, _, _) => ResumeStep(s)
  }

  val fromResumeOp: ResumeOp ~> WorkflowOp = λ[ResumeOp ~> WorkflowOp] {
    case WaitingPar(pstep) =>
      val pstepz =
        pstep.compile(
          λ[IndexedResume ~> IndexedWorkflow](ir =>
            IndexedWorkflow(ir.parNum, ir.workflow.compile(fromResumeOp))
          )
        )
      FromPar(pstepz)

    case RunningPar(_, pstep) =>
      val pstepz =
        pstep.compile(
          λ[IndexedResume ~> IndexedWorkflow](ir =>
            IndexedWorkflow(ir.parNum, ir.workflow.compile(fromResumeOp))
          )
        )
      FromPar(pstepz)

    case ResumeStep(s) => s
  }

  def parStarted[A](
      eventId: EventId,
      parNum: Int,
      payload: ParStarted,
      traceIds: List[EventId]
  ): ResumeOp ~> ResumeOp = λ[ResumeOp ~> ResumeOp] { op =>
    traceIds match {
      case head :: next =>
        op match {
          case RunningPar(id, subWorkflow) if id == head =>
            val subz = subWorkflow.compile(λ[IndexedResume ~> IndexedResume] { fa =>
              IndexedResume(
                fa.parNum,
                fa.workflow.compile(parStarted(eventId, fa.parNum, payload, next))
              )
            })
            RunningPar(id, subz)

          case other => other
        }

      case Nil =>
        op match {
          case WaitingPar(sub) if parNum == payload.parNum => RunningPar(eventId, sub)
          case other                                       => other
        }
    }
  }

  type Eff[A] = StateT[Either[StateError, *], Option[Step[_]], A]
  type FAR[A] = FreeApplicative[IndexedResume, A]
  type EffFAR[A] = Eff[FAR[A]]

  object Eff {
    def pure[A](value: A): Eff[A] = StateT.pure(value)

    def error[A](err: StateError): Eff[A] = StateT.liftF(Left(err))

    def feed[A](step: Step[_], value: A): Eff[A] = StateT(_ => Right((Some(step), value)))

    object FAR {
      implicit val farApplicative: Applicative[EffFAR] = Applicative[Eff].compose[FAR]
    }
  }

  def completedStep[A](
      parNum: Int,
      payload: StepCompleted,
      traceIds: List[EventId],
      op: ResumeOp[A]
  ): Eff[Free[ResumeOp, A]] = {
    traceIds match {
      case head :: next =>
        op match {
          case RunningPar(id, subWorkflow) if id == head =>
            val subz =
              subWorkflow.foldMap(
                λ[IndexedResume ~> EffFAR] { fa =>
                  fa.workflow.resume match {
                    case Left(op) =>
                      completedStep(fa.parNum, payload, next, op)
                        .map(_.flatten)
                        .map(fb => FreeApplicative.lift(IndexedResume(fa.parNum, fb)))
                    case Right(v) => Eff.pure(FreeApplicative.pure(v))
                  }
                }
              )(Eff.FAR.farApplicative)

            subz.map(freeApp =>
              freeApp.compile(λ[IndexedResume ~> Free[ResumeOp, *]](_.workflow)).fold.resume match {
                case Left(_)  => Free.liftF(RunningPar(id, freeApp))
                case Right(v) => Free.pure(v)
              }
            )

          case other =>
            Eff.pure(Free.liftF(other))
        }

      case Nil =>
        op match {
          case ResumeStep(step) if parNum == payload.parNum && step.name == payload.step =>
            step.circeDecoder.decodeJson(payload.payload) match {
              case Left(err)    => Eff.error(CantDecodePayload(err.message))
              case Right(value) => Eff.feed(step, Free.pure(value))
            }
          case other => Eff.pure(Free.liftF(other))
        }
    }
  }
}

object Resume {

  case class ResumeRunPayload[I, O](version: Version, workflow: Workflow[O])
  case class CompensateRunPayload(version: Version, step: List[Step[_]])

  sealed trait ResumeState[A]
  object ResumeState {
    case class Init[A]() extends ResumeState[A]
    case class Started[A](
        paths: Map[EventId, EventId],
        workflow: Free[ResumeOp, A],
        executedSteps: List[Step[_]]
    ) extends ResumeState[A]
    case class Completed[A](
        value: A,
        executedSteps: List[Step[_]]
    ) extends ResumeState[A]
    case class Failed[A](
        paths: Map[EventId, EventId],
        workflow: Free[ResumeOp, A],
        executedSteps: List[Step[_]]
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
      case (Failed(_, workflow, _), version) =>
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
      case (Failed(_, _, steps), version) =>
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
      case Init()                  => init(fsm, event)
      case s @ Started(_, _, _)    => started(s, event)
      case Completed(_, _)         => completed(event)
      case s @ Failed(_, _, _)     => failed(s, event)
      case CompensationStarted(_)  => compensationStarted(event)
      case CompensationFailed(_)   => compensationFailed(event)
      case CompensationCompleted() => compensationCompleted(event)
    }

  def init[I, O](fsm: FSM[I, O], event: Event)(implicit
      decoder: Decoder[I]
  ): Either[StateError, ResumeState[O]] = {
    event.payload match {
      case WorkflowStarted(_, input) =>
        for {
          i <- decoder.decodeJson(input).leftMap(e => CantDecodePayload(e.message))
          w = fsm.workflow(i).compile(ResumeOp.toResumeOp)
        } yield Started(Map.empty, w, Nil)
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
      case p @ StepCompleted(_, _, correlationId, _) =>
        state.workflow.resume match {
          case Left(op) =>
            val result =
              ResumeOp
                .completedStep(1, p, traceIds(state.paths, correlationId), op)
                .map(_.flatten)
                .run(None)

            result match {
              case Right((Some(step), updatedWorkflow)) =>
                Right(
                  state.copy(
                    executedSteps = step :: state.executedSteps,
                    workflow = updatedWorkflow
                  )
                )

              case Right((None, _)) =>
                Left(IncoherentState("A completed step doesn't exist."))

              case Left(err) => Left(err)
            }

          case Right(_) =>
            Left(IncoherentState("workflow completed but diverge from event source."))
        }

      case WorkflowCompleted =>
        state.workflow.resume match {
          case Left(_)  => Left(IncoherentState("workflow isn't completed but event source is."))
          case Right(v) => Right(Completed(v, state.executedSteps))
        }

      case WorkflowFailed => Right(Failed(state.paths, state.workflow, state.executedSteps))
      case evt            => Left(IncoherentState(s"Oops : $evt"))
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
      case io.rlecomte.fsm.CompensationStarted => Right(CompensationStarted(Nil))
      case io.rlecomte.fsm.WorkflowResumed =>
        Right(Started(state.paths, state.workflow, state.executedSteps))
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

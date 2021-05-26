package io.rlecomte.fsm.runtime

import cats.implicits._
import cats.free.Free
import cats.~>
import cats.Functor
import io.rlecomte.fsm.Workflow.WorkflowOp
import cats.free.FreeApplicative
import cats.data.StateT
import cats.Applicative
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm._

sealed trait ResumeOp[A]

object ResumeOp {
  type CompletedStep[A] = StateT[Either[StateError, *], Option[Step[_]], A]
  type FAR[A] = FreeApplicative[IndexedResume, A]
  type CompletedStepPar[A] = CompletedStep[FAR[A]]

  object CompletedStep {
    def pure[A](value: A): CompletedStep[A] = StateT.pure(value)

    def error[A](err: StateError): CompletedStep[A] = StateT.liftF(Left(err))

    def feed[A](step: Step[_], value: A): CompletedStep[A] = StateT(_ => Right((Some(step), value)))

    object FAR {
      implicit val farApplicative: Applicative[CompletedStepPar] =
        Applicative[CompletedStep].compose[FreeApplicative[IndexedResume, *]]
    }
  }

  final case class IndexedResume[A](parNum: Int, workflow: Free[ResumeOp, A])

  final case class ResumeStep[A](sub: Step[A]) extends ResumeOp[A]
  final case class RunningPar[A](id: EventId, sub: FreeApplicative[IndexedResume, A])
      extends ResumeOp[A]
  final case class WaitingPar[A](sub: FreeApplicative[IndexedResume, A]) extends ResumeOp[A]

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

  def completedStep[A](
      parNum: Int,
      payload: StepCompleted,
      traceIds: List[EventId],
      op: ResumeOp[A]
  ): CompletedStep[Free[ResumeOp, A]] = {
    traceIds match {
      case head :: next =>
        op match {
          case RunningPar(id, subWorkflow) if id == head =>
            val subz =
              subWorkflow.foldMap(
                λ[IndexedResume ~> CompletedStepPar] { fa =>
                  fa.workflow.resume match {
                    case Left(op) =>
                      completedStep(fa.parNum, payload, next, op)
                        .map(_.flatten)
                        .map(fb => FreeApplicative.lift(IndexedResume(fa.parNum, fb)))
                    case Right(v) => CompletedStep.pure(FreeApplicative.pure(v))
                  }
                }
              )(CompletedStep.FAR.farApplicative)

            subz.map(freeApp =>
              freeApp.compile(λ[IndexedResume ~> Free[ResumeOp, *]](_.workflow)).fold.resume match {
                case Left(_)  => Free.liftF(RunningPar(id, freeApp))
                case Right(v) => Free.pure(v)
              }
            )

          case other =>
            CompletedStep.pure(Free.liftF(other))
        }

      case Nil =>
        op match {
          case ResumeStep(step) if parNum == payload.parNum && step.name == payload.step =>
            step.circeDecoder.decodeJson(payload.payload) match {
              case Left(err)    => CompletedStep.error(CantDecodePayload(err.message))
              case Right(value) => CompletedStep.feed(step, Free.pure(value))
            }
          case other => CompletedStep.pure(Free.liftF(other))
        }
    }
  }
}

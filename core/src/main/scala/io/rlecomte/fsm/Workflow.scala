package io.rlecomte.fsm

import cats.Applicative
import cats.Functor
import cats.Monad
import cats.Parallel
import cats.arrow.FunctionK
import cats.data.State
import cats.effect.IO
import cats.free.Free
import cats.free.Free.liftF
import cats.free.FreeApplicative
import cats.~>
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json

object Workflow {

  type Workflow[A] = Free[WorkflowOp, A]
  type ParWorkflow[A] = State[Int, FreeApplicative[IndexedWorkflow, A]]

  sealed trait RetryStrategy
  case object NoRetry extends RetryStrategy
  case class LinearRetry(nbRetry: Int) extends RetryStrategy

  sealed trait WorkflowOp[A]
  case class Step[A](
      name: String,
      effect: IO[(Json, A)],
      compensate: IO[Unit] = IO.unit,
      retryStrategy: RetryStrategy,
      circeDecoder: Decoder[A]
  ) extends WorkflowOp[A]
  case class FromPar[A](pstep: FreeApplicative[IndexedWorkflow, A]) extends WorkflowOp[A]

  case class IndexedWorkflow[A](parNum: Int, workflow: Workflow[A])

  implicit val functorWorkflowOp: Functor[WorkflowOp] = new Functor[WorkflowOp] {
    override def map[A, B](fa: WorkflowOp[A])(f: A => B): WorkflowOp[B] = fa match {
      case Step(name, effect, compensate, retryStrategy, circeDecoder) =>
        Step(name, effect.map(t => (t._1, f(t._2))), compensate, retryStrategy, circeDecoder.map(f))

      case FromPar(pstep) => FromPar(pstep.map(f))
    }
  }

  def pure[A](value: A): Workflow[A] = Free.pure(value)

  def step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit] = IO.unit,
      retryStrategy: RetryStrategy = NoRetry
  )(implicit encoder: Encoder[A], decoder: Decoder[A]): Workflow[A] = {
    liftF[WorkflowOp, A](
      Step(name, effect.map(r => (encoder(r), r)), compensate, retryStrategy, decoder)
    )
  }

  def fromPar[A](par: ParWorkflow[A]): Workflow[A] = {
    liftF[WorkflowOp, A](FromPar(par.runEmptyA.value))
  }

  implicit val parallel: Parallel[Workflow] = new Parallel[Workflow] {
    type F[A] = ParWorkflow[A]

    override def sequential: ParWorkflow ~> Workflow =
      new FunctionK[ParWorkflow, Workflow] {
        override def apply[A](fa: ParWorkflow[A]): Workflow[A] = fromPar(fa)
      }

    override def parallel: Workflow ~> ParWorkflow =
      new FunctionK[Workflow, ParWorkflow] {
        override def apply[A](fa: Workflow[A]): ParWorkflow[A] =
          State(idx => (idx + 1, FreeApplicative.lift(IndexedWorkflow(idx, fa))))
      }

    override def applicative: Applicative[ParWorkflow] =
      Applicative[State[Int, *]].compose(Applicative[FreeApplicative[IndexedWorkflow, *]])

    override def monad: Monad[Workflow] = Monad[Workflow]

  }
}

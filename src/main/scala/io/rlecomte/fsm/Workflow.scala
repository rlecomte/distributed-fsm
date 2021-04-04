package io.rlecomte.fsm

import cats.effect.IO
import cats.Monad
import cats.free.Free
import cats.free.Free.liftF
import cats.arrow.FunctionK
import cats.free.FreeApplicative
import cats.Parallel
import cats.Applicative
import cats.~>
import io.circe.Encoder

object Workflow {

  type Workflow[A] = Free[WorkflowOp, A]
  type ParWorkflow[A] = FreeApplicative[WorkflowOp, A]

  sealed trait RetryStrategy
  case object NoRetry extends RetryStrategy
  case class LinearRetry(nbRetry: Int) extends RetryStrategy

  sealed trait WorkflowOp[A]
  case class Step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit] = IO.unit,
      retryStrategy: RetryStrategy,
      circeEncoder: Encoder[A]
  ) extends WorkflowOp[A]
  case class FromPar[A](pstep: ParWorkflow[A]) extends WorkflowOp[A]
  case class FromSeq[A](step: Workflow[A]) extends WorkflowOp[A]

  def step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit],
      retryStrategy: RetryStrategy = NoRetry
  )(implicit encoder: Encoder[A]): Workflow[A] = {
    liftF[WorkflowOp, A](Step(name, effect, compensate, retryStrategy, encoder))
  }

  def fromPar[A](par: ParWorkflow[A]): Workflow[A] = {
    liftF[WorkflowOp, A](FromPar(par))
  }

  def fromSeq[A](seq: Workflow[A]): ParWorkflow[A] = {
    cats.free.FreeApplicative.lift[WorkflowOp, A](FromSeq(seq))
  }

  implicit val parallel: Parallel[Workflow] = new Parallel[Workflow] {
    type F[A] = ParWorkflow[A]

    override def sequential: ParWorkflow ~> Workflow =
      new FunctionK[ParWorkflow, Workflow] {
        override def apply[A](fa: ParWorkflow[A]): Workflow[A] = fromPar(fa)
      }

    override def parallel: Workflow ~> ParWorkflow =
      new FunctionK[Workflow, ParWorkflow] {
        override def apply[A](fa: Workflow[A]): ParWorkflow[A] = fromSeq(fa)
      }

    override def applicative: Applicative[ParWorkflow] =
      implicitly[Applicative[ParWorkflow]]

    override def monad: Monad[Workflow] = implicitly[Monad[Workflow]]

  }
}

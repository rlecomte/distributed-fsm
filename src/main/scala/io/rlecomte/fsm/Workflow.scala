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
import io.circe.Decoder

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
      circeEncoder: Encoder[A],
      circeDecoder: Decoder[A]
  ) extends WorkflowOp[A]
  case class AlreadyProcessedStep[A](name: String, result: A, compensate: IO[Unit])
      extends WorkflowOp[A]
  case class FromPar[A](pstep: ParWorkflow[A]) extends WorkflowOp[A]
  case class FromSeq[A](step: Workflow[A]) extends WorkflowOp[A]
  case class Suspend[A, B](
      name: String,
      first: SuspendToken => IO[Unit],
      second: A => IO[B],
      compensate: IO[Unit] = IO.unit,
      decoder: Decoder[A],
      encoder: Encoder[B]
  ) extends WorkflowOp[B]
  case class PendingSuspend[A](
      name: String,
      second: IO[A],
      compensate: IO[Unit] = IO.unit,
      encoder: Encoder[A]
  ) extends WorkflowOp[A]

  def pure[A](value: A): Workflow[A] = Free.pure(value)

  def step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit] = IO.unit,
      retryStrategy: RetryStrategy = NoRetry
  )(implicit encoder: Encoder[A], decoder: Decoder[A]): Workflow[A] = {
    liftF[WorkflowOp, A](Step(name, effect, compensate, retryStrategy, encoder, decoder))
  }

  def suspend[A, B](
      name: String,
      first: SuspendToken => IO[Unit],
      second: A => IO[B],
      compensate: IO[Unit]
  )(implicit decoder: Decoder[A], encoder: Encoder[B]): Workflow[B] = {
    liftF[WorkflowOp, B](Suspend(name, first, second, compensate, decoder, encoder))
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

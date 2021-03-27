package example

import cats.effect.IO
import cats.Monad
import cats.free.Free
import cats.free.Free.liftF
import cats.effect.IOApp
import cats.effect.ExitCode
import cats.arrow.FunctionK
import cats.data.WriterT
import cats.implicits._
import cats.data.StateT
import cats.effect.concurrent.Ref

object Workflow {

  type Workflow[A] = Free[Step, A]

  case class Step[A](name: String, effect: IO[A], compensate: IO[Unit])

  def step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit]
  ): Workflow[A] = {
    liftF[Step, A](Step(name, effect, compensate))
  }
}

object WorkflowRuntime {
  import Workflow._
  type RollbackRef = Ref[IO, IO[Unit]]

  private class Run(private val rollback: RollbackRef) {

    private def tellIO(rollbackStep: IO[Unit]): IO[Unit] =
      rollback.modify(steps => (rollbackStep *> steps, ()))

    private val runCompensation: IO[Unit] = rollback.get.flatten

    private val foldIO: FunctionK[Step, IO] =
      new FunctionK[Step, IO] {
        override def apply[A](
            step: Step[A]
        ): IO[A] =
          step.effect.attempt.flatMap {
            case Right(a)  => tellIO(step.compensate).as(a)
            case Left(err) => runCompensation *> IO.raiseError(err)
          }
      }

    def toIO[A](workflow: Workflow[A]): IO[A] = {
      workflow.foldMap(foldIO)
    }
  }

  def run[A](workflow: Workflow[A]): IO[A] = for {
    ref <- Ref.of[IO, IO[Unit]](IO.unit)
    result <- new Run(ref).toIO(workflow)
  } yield result
}

object Hello extends IOApp {
  import Workflow._

  val step1 =
    step("step 1", IO(println("coucou")), IO(println("revert step 1")))

  val step2 =
    step("step 2", IO(println("comment")), IO(println("revert step 2")))

  val step3 = step("step 3", IO(println("va?")), IO(println("revert step 3")))

  val step4 = step(
    "step 3",
    IO(println("Oh no!")) *> IO.raiseError(new RuntimeException("oops")),
    IO(println("should not be execute"))
  )

  val program: Workflow[Unit] = for {
    _ <- step1
    _ <- step2
    _ <- step3
    _ <- step4
  } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    WorkflowRuntime.run(program).as(ExitCode.Success)
}

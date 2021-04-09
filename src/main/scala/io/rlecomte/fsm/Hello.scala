package io.rlecomte.fsm

import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import cats.effect.ExitCode

object Hello extends IOApp {
  import Workflow._

  val step1 =
    step("step 1", IO(println("coucou")), IO(println("revert step 1")))

  val step2 =
    step("step 2", IO(println("comment")), IO(println("revert step 2")))

  val step31 =
    step(
      "step 3-1",
      IO(Thread.sleep(1000)) *> IO(println("va?")),
      IO(println("revert step 3-1"))
    )
  val step32 =
    step(
      "step 3-2",
      IO(Thread.sleep(2000)) *> IO(println("va?")),
      IO(println("revert step 3-2"))
    )

  val step4 = step[Unit](
    "step 4",
    IO(println("Oh no!")) *> IO.raiseError(new RuntimeException("oops")),
    IO(println("should not be execute")),
    retryStrategy = LinearRetry(3)
  )

  val program: FSM[Unit, Unit] = FSM.define("simple state machine") { _ =>
    for {
      _ <- step1
      _ <- step2
      _ <- (step31, step32).parTupled
      _ <- step4
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    implicit0(backend: BackendEventStore) <- InMemoryBackendEventStore.newStore
    _ <- program.compile.run(()).attempt
    _ <- backend.readAllEvents.flatMap(v => v.traverse(evt => IO(println(evt.payload))))
    summary <- new Projection(backend).getSummary
    details <- summary.jobs.traverse { case (_, runId) =>
      new Projection(backend).getJobDetail(runId)
    }
    _ <- IO(println(summary))
    _ <- IO(println(details))
  } yield ExitCode.Success
}

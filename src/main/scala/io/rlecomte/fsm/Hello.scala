package io.rlecomte.fsm

import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import cats.effect.ExitCode
import cats.effect.concurrent.Ref

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

  val step4 = step(
    "step 4",
    IO(println("Oh no!")) *> IO.raiseError(new RuntimeException("oops")),
    IO(println("should not be execute"))
  )

  val program: Workflow[Unit] = for {
    _ <- step1
    _ <- step2
    _ <- (step31, step32).parTupled
    _ <- step4
  } yield ()

  override def run(args: List[String]): IO[ExitCode] = for {
    refStore <- Ref.of[IO, Vector[WorkflowEvent]](Vector())
    store = InMemoryWorkflowStore(refStore)
    _ <- WorkflowRuntime.run(store)(program).attempt
    _ <- refStore.get.flatMap(v => v.traverse(evt => IO(println(evt))))
  } yield ExitCode.Success
}

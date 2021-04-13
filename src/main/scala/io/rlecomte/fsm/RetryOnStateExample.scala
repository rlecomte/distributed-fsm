package io.rlecomte.fsm

import cats.effect.{ExitCode, IO, IOApp}
import io.rlecomte.fsm.Workflow.step
import cats.implicits._

object RetryOnStateExample extends IOApp {

  val step1 =
    step("step 1", IO(println("step 1")), IO(println("revert step 1")))

  val step2 =
    step("step 2", IO(println("step 2")), IO(println("revert step 2")))

  val step3 =
    step("step 3", IO[Unit](throw new IllegalStateException("NANI")), IO(println("revert step 3")))

  val step3Correct = (x: Int) => step(s"step 3 with value ${x}", IO(println("step 3 - Correct")), IO(println("revert step 3")))

  val step4 =
    step("step 4", IO(println("step 4")), IO(println("revert step 4")))

  val step0 = (x: Int) => step("step 0", IO(println(x)), IO(println("revert step 4")))

  val failingProgram = FSM.define("retry on state example") { x: Int => for {
      _ <- step0(x)
      _ <- step1
      _ <- step2
      _ <- step3
      _ <- step4
    } yield ()
  }

  val correctProgram = FSM.define("retry on state example") { x: Int => for {
    _ <- step0(x)
    _ <- step1
    _ <- step2
    _ <- step3Correct(x)
    _ <- step4
  } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    implicit0(backend: BackendEventStore) <- InMemoryBackendEventStore.newStore
    (runId, _) <- failingProgram.compile().run(5)
    _ <- IO(println(runId))
    _ <- backend.readAllEvents.flatMap(v => v.traverse(evt => IO(println(evt.payload))))
    _ <- correctProgram.retry(runId, backend).flatMap(_.run)
    _ <- backend.readAllEvents.flatMap(v => v.traverse(evt => IO(println(evt.payload))))
  } yield ExitCode.Success
}

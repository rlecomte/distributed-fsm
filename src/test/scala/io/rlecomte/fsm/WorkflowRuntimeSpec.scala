package io.rlecomte.fsm

import cats.effect.IO
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.Workflow
import io.rlecomte.fsm.WorkflowStarted
import io.rlecomte.fsm.WorkflowCompleted
import cats.effect.testing.minitest.IOTestSuite
import cats.effect.kernel.Ref
import cats.implicits._
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

object WorkflowRuntimeSpec extends IOTestSuite with WorkflowTestSuite {

  testW("An empty FSM execution should generate started and completed event") { implicit backend =>
    val program = FSM
      .define[Unit, Unit]("empty FSM") { _ =>
        Workflow.pure(())
      }

    val expected = List(WorkflowStarted("empty FSM"), WorkflowCompleted)

    for {
      _ <- program.compile.runSync(())
      events <- backend.readAllEvents
    } yield assertEquals(events.map(_.payload), expected)
  }

  testW("A single step FSM execution should generate step event") { implicit backend =>
    val program = for {
      ref <- Ref.of[IO, Boolean](false)
      fsm = FSM
        .define[Unit, Unit]("single FSM") { _ =>
          Workflow.step(name = "single step", effect = ref.set(true))
        }
    } yield (fsm, ref)

    for {
      (fsm, ref) <- program
      _ <- fsm.compile.runSync(())

      events <- backend.readAllEvents
      _ = assertEquals(events.length, 4)
      _ <- checkPayload[WorkflowStarted](events(0))(e => assertEquals(e.workflow, "single FSM"))
      _ <- checkPayload[StepStarted](events(1))(e => assertEquals(e.step, "single step"))
      _ <- checkPayload[StepCompleted](events(2))(identity)
      _ <- checkPayload[WorkflowCompleted.type](events(3))(identity)

      _ <- ref.get.map(assertEquals(_, true))
    } yield ()
  }

  testW("A multiple sequential step FSM execution should generate all steps events") {
    implicit backend =>
      val program = for {
        ref <- Ref.of[IO, Boolean](false)
        ref2 <- Ref.of[IO, Boolean](false)
        ref3 <- Ref.of[IO, Boolean](false)
        fsm = FSM
          .define[Unit, Unit]("multiple sequential step FSM") { _ =>
            for {
              _ <- Workflow.step(name = "step1", effect = ref.set(true))
              _ <- Workflow.step(name = "step2", effect = ref2.set(true))
              _ <- Workflow.step(name = "step3", effect = ref3.set(true))
            } yield ()
          }
      } yield (fsm, ref, ref2, ref3)

      for {
        (fsm, ref, ref2, ref3) <- program
        _ <- fsm.compile.runSync(())

        events <- backend.readAllEvents
        _ = assertEquals(events.length, 8)
        _ = checkPayload[WorkflowStarted](events(0))(e =>
          assertEquals(e.workflow, "multiple sequential step FSM")
        )
        _ <- checkPayload[StepStarted](events(1))(p => assertEquals(p.step, "step1"))
        _ <- checkPayload_[StepCompleted](events(2))
        _ <- checkPayload[StepStarted](events(3))(e => assertEquals(e.step, "step2"))
        _ <- checkPayload_[StepCompleted](events(4))
        _ <- checkPayload[StepStarted](events(5))(e => assertEquals(e.step, "step3"))
        _ <- checkPayload_[StepCompleted](events(6))
        _ <- checkPayload_[WorkflowCompleted.type](events(7))

        _ <- ref.get.map(assertEquals(_, true))
        _ <- ref2.get.map(assertEquals(_, true))
        _ <- ref3.get.map(assertEquals(_, true))
      } yield ()
  }

  testW("A multiple parallel step FSM execution should generate all steps events") {
    implicit backend =>
      val program = for {
        ref <- Ref.of[IO, Boolean](false)
        ref2 <- Ref.of[IO, Boolean](false)
        ref3 <- Ref.of[IO, Boolean](false)
        fsm = FSM
          .define[Unit, Unit]("multiple parallel step FSM") { _ =>
            List(
              Workflow.step(
                name = "step1",
                effect = ref.set(true)
              ),
              Workflow.step(
                name = "step2",
                effect = ref2.set(true)
              ),
              Workflow.step(
                name = "step3",
                effect = ref3.set(true)
              )
            ).parSequence.void
          }
      } yield (fsm, ref, ref2, ref3)

      for {
        (fsm, ref, ref2, ref3) <- program
        _ <- fsm.compile.runSync(())

        events <- backend.readAllEvents
        _ = assertEquals(events.length, 12)
        _ = checkPayload[WorkflowStarted](events(0))(e =>
          assertEquals(e.workflow, "multiple parallel step FSM")
        )

        _ <- ref.get.map(assertEquals(_, true))
        _ <- ref2.get.map(assertEquals(_, true))
        _ <- ref3.get.map(assertEquals(_, true))
      } yield ()
  }

  testW("A failing step shouldn't interrupt parallel step execution") { implicit backend =>
    val program = for {
      ref <- Ref.of[IO, Boolean](false)
      fsm = FSM
        .define[Unit, Unit]("failing FSM") { _ =>
          (
            Workflow.step(
              name = "failing step",
              effect = IO.raiseError(new RuntimeException("Oops")).void
            ),
            Workflow.step(
              name = "successful step",
              effect = IO.sleep(FiniteDuration(30, TimeUnit.MILLISECONDS)) *> ref.set(true)
            )
          ).parTupled.void
        }
    } yield (fsm, ref)

    for {
      (fsm, ref) <- program
      _ <- fsm.compile.runSync(()).attempt
      _ <- ref.get.map(assertEquals(_, true))
    } yield ()
  }
}

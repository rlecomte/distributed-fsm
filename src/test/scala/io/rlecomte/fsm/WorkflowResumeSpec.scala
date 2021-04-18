package io.rlecomte.fsm

import cats.effect.testing.minitest.IOTestSuite
import cats.effect.IO
import cats.implicits._
import io.rlecomte.fsm.WorkflowResume.RunHistory
import io.circe.syntax._

object WorkflowResumeSpec extends IOTestSuite with WorkflowTestSuite {

  testW("Replaying a failed FSM should start from the failed step") { implicit backend =>
    val failingProgram = FSM
      .define[Unit, Unit]("failing FSM") { _ =>
        for {
          _ <- Workflow.step("step 1", IO.unit)
          _ <- (
            Workflow.step[Unit]("step 2.1", IO.raiseError(new RuntimeException("Oops"))),
            Workflow.step("step 2.2", IO.unit)
          ).parTupled
          _ <- Workflow.step("step 3", IO.unit)
        } yield ()
      }

    val program = FSM
      .define[Unit, Unit]("failing FSM") { _ =>
        for {
          _ <- Workflow.step("step 1", IO.unit)
          _ <- (
            Workflow.step[Unit]("step 2.1", IO.unit),
            Workflow.step("step 2.2", IO.unit)
          ).parTupled
          _ <- Workflow.step("step 3", IO.unit)
        } yield ()
      }

    for {
      (runId, _) <- failingProgram.compile.run(())
      events <- backend.readAllEvents
      payload = events.map(_.payload)
      _ = assert(payload.contains(WorkflowFailed))
      _ = assert(!payload.contains(WorkflowCompleted))
      _ = assertEquals(
        payload.collect { case StepCompleted(step, _) if step == "step 1" => () }.length,
        1
      )
      _ = assertEquals(
        payload.collect { case StepCompleted(step, _) if step == "step 2.1" => () }.length,
        0
      )
      _ = assertEquals(
        payload.collect { case StepCompleted(step, _) if step == "step 3" => () }.length,
        0
      )

      _ <- WorkflowResume
        .resume(
          backend,
          program,
          RunHistory(
            runId,
            (),
            Map(
              "step 1" -> ().asJson
            )
          )
        )
        .run

      newEvents <- backend.readAllEvents
      newPayload = newEvents.map(_.payload)

      _ = assert(newPayload.contains(WorkflowCompleted))
      _ = assertEquals(
        newPayload.collect { case StepCompleted("step 1", _) => () }.length,
        1
      )
      _ = assertEquals(
        newPayload.collect { case StepCompleted("step 2.1", _) => () }.length,
        1
      )
      _ = assertEquals(
        newPayload.collect { case StepCompleted("step 2.2", _) => () }.length,
        1
      )
      _ = assertEquals(
        newPayload.collect { case StepCompleted("step 3", _) => () }.length,
        1
      )
    } yield ()
  }

  testW("Compensate a failed FSM should compensate successful step") { implicit backend =>
    val failingProgram = FSM
      .define[Unit, Unit]("failing FSM") { _ =>
        for {
          _ <- Workflow.step("step 1", IO.unit)
          _ <- (
            Workflow.step("step 2.1", IO.unit),
            Workflow.step("step 2.2", IO.unit)
          ).parTupled
          _ <- Workflow.step[Unit]("step 3", IO.raiseError(new RuntimeException("Oops")))
        } yield ()
      }

    for {
      (runId, _) <- failingProgram.compile.run(())

      _ <- WorkflowResume
        .resume(
          backend,
          failingProgram,
          RunHistory(
            runId,
            (),
            Map(
              "step 1" -> ().asJson,
              "step 2.1" -> ().asJson,
              "step 2.2" -> ().asJson
            )
          )
        )
        .compensate

      newEvents <- backend.readAllEvents
      newPayload = newEvents.map(_.payload)
      _ = assertEquals(
        newPayload.collect { case StepCompensationCompleted("step 1") => () }.length,
        1
      )
      _ = assertEquals(
        newPayload.collect { case StepCompensationCompleted("step 2.1") => () }.length,
        1
      )
      _ = assertEquals(
        newPayload.collect { case StepCompensationCompleted("step 2.2") => () }.length,
        1
      )
    } yield ()
  }
}

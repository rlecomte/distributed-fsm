package io.rlecomte.fsm

import cats.data.WriterT
import cats.effect.IO
import cats.implicits._
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.RunId
import io.rlecomte.fsm.Workflow._
import io.rlecomte.fsm.runtime.StateError
import io.rlecomte.fsm.runtime.WorkflowRuntime
import io.rlecomte.fsm.store.EventStore
import org.scalacheck.Arbitrary
import org.scalacheck.Gen

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object test {

  case class ExecutedActions(runId: RunId, executedActions: List[List[Action]])
  case class WorkflowExecutionPlan(value: IO[ExecutedActions])

  object WorkflowExecutionPlan {
    implicit def arbitrary(implicit store: EventStore): Arbitrary[WorkflowExecutionPlan] =
      Arbitrary[WorkflowExecutionPlan] {
        for {
          workflow <- Gen.sized(workflowGen)
          nbPar <- Gen.choose(2, 10)
          actions <- Gen.listOfN(nbPar, Gen.choose(1, 30).flatMap(i => Gen.listOfN(i, actionGen)))
        } yield WorkflowExecutionPlan(randomRun(store, workflow, actions))
      }
  }

  def stepGen: Gen[Workflow[Unit]] = for {
    name <- Gen.uuid.map(_.toString())
    stepGen = Gen
      .choose(1, 15)
      .map(FiniteDuration(_, TimeUnit.MILLISECONDS))
      .map(d => IO(println(s"step exec during $d millis")) *> IO.sleep(d))
    effect <- Gen.frequency(
      (29, stepGen),
      (1, Gen.const(IO.raiseError(new RuntimeException("Oops"))))
    )
    compensateEffect <- Gen.frequency(
      (29, stepGen),
      (1, Gen.const(IO.raiseError(new RuntimeException("Oops"))))
    )
  } yield step(name, effect, _ => compensateEffect)

  def serialStepsGen(size: Int): Gen[Workflow[Unit]] = for {
    first <- workflowGen(size)
    second <- workflowGen(size)
  } yield first.flatMap(_ => second)

  def parStepsGen(size: Int): Gen[Workflow[Unit]] = for {
    nbPar <- Gen.choose[Int](2, 5)
    steps <- Gen.listOfN(
      nbPar,
      workflowGen(size)
    )
  } yield steps.parSequence.as(())

  def workflowGen(size: Int): Gen[Workflow[Unit]] = Gen.lzy {
    if (size <= 0) stepGen
    else {
      Gen.frequency(
        (1, stepGen),
        (1, serialStepsGen(size - 1)),
        (1, parStepsGen(size - 1))
      )
    }
  }

  sealed trait Action
  case object Resume extends Action
  case object Compensate extends Action

  def actionGen: Gen[Action] = Gen.frequency((9, Resume), (1, Compensate))

  def randomRun(
      store: EventStore,
      workflow: Workflow[Unit],
      actions: List[List[Action]]
  ): IO[ExecutedActions] = for {
    fib <- WorkflowRuntime.start(
      store,
      FSM[Unit, Unit]("test", _ => workflow, Codec.from(Decoder[Unit], Encoder[Unit])),
      ()
    )
    _ <- fib.join
    actions <- actions.parTraverse { workerAction =>
      workerAction
        .foldMapM[WriterT[IO, List[Action], *], Unit] { action =>
          WriterT
            .liftF[IO, List[Action], Either[StateError, Unit]](
              runAction(store, workflow, fib.runId, action)
            )
            .map {
              case Right(_) => WriterT.tell[IO, List[Action]](List(action))
              case Left(_)  => WriterT.value[IO, List[Action], Unit](())
            }
        }
        .run
        .map(_._1)
    }
  } yield ExecutedActions(fib.runId, actions)

  def runAction(
      store: EventStore,
      workflow: Workflow[Unit],
      runId: RunId,
      action: Action
  ): IO[Either[StateError, Unit]] = action match {
    case Resume =>
      WorkflowRuntime
        .resume(
          store,
          FSM[Unit, Unit]("test", _ => workflow, Codec.from(Decoder[Unit], Encoder[Unit])),
          runId
        )
        .flatMap {
          case Right(v)  => v.join
          case Left(err) => IO.pure(Left(err))
        }
        .attempt
        .as(Right(()))

    case Compensate =>
      WorkflowRuntime
        .compensate(
          store,
          FSM[Unit, Unit]("test", _ => workflow, Codec.from(Decoder[Unit], Encoder[Unit])),
          runId
        )
        .flatMap {
          case Right(v)  => v.join
          case Left(err) => IO.pure(Left(err))
        }
        .attempt
        .as(Right(()))
  }
}

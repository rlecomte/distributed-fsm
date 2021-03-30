package example

import cats.effect.IO
import cats.Monad
import cats.free.Free
import cats.free.Free.liftF
import cats.effect.IOApp
import cats.effect.ExitCode
import cats.arrow.FunctionK
import cats.implicits._
import cats.effect.concurrent.Ref
import java.util.UUID
import java.{util => ju}
import cats.free.FreeApplicative
import cats.effect.ContextShift
import cats.Parallel
import cats.Applicative
import cats.~>

object Workflow {

  type Workflow[A] = Free[WorkflowOp, A]
  type ParWorkflow[A] = FreeApplicative[WorkflowOp, A]

  sealed trait WorkflowOp[A]
  case class Step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit] = IO.unit
  ) extends WorkflowOp[A]
  case class FromPar[A](pstep: ParWorkflow[A]) extends WorkflowOp[A]
  case class FromSeq[A](step: Workflow[A]) extends WorkflowOp[A]

  def step[A](
      name: String,
      effect: IO[A],
      compensate: IO[Unit]
  ): Workflow[A] = {
    liftF[WorkflowOp, A](Step(name, effect, compensate))
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

sealed trait WorkflowEvent
case class WorkflowStarted(workflow: String, runId: UUID) extends WorkflowEvent
case class WorkflowCompleted(runId: UUID) extends WorkflowEvent
case class WorkflowFailed(runId: UUID) extends WorkflowEvent
case class WorkflowStepStarted(step: String, id: UUID) extends WorkflowEvent
case class WorkflowStepCompleted(step: String, id: UUID, payload: String)
    extends WorkflowEvent
case class WorkflowStepFailed(step: String, id: UUID, error: Throwable)
    extends WorkflowEvent
case class WorkflowCompensationStarted(step: String, id: UUID)
    extends WorkflowEvent
case class WorkflowCompensationCompleted(step: String, id: UUID)
    extends WorkflowEvent
case class WorkflowCompensationFailed(step: String, id: UUID, error: Throwable)
    extends WorkflowEvent

trait WorkflowStore {
  def logWorkflowStarted(name: String, runId: UUID): IO[WorkflowTracer]

  def logWorkflowCompleted(runId: UUID): IO[Unit]

  def logWorkflowFailed(runId: UUID): IO[Unit]

  def logWorkflowExecution[A](f: WorkflowTracer => IO[A]): IO[A] = for {
    runId <- IO(UUID.randomUUID())
    tracer <- logWorkflowStarted("foo", runId) //TODO name workflow
    either <- f(tracer).attempt
    result <- either match {
      case Right(r)  => logWorkflowCompleted(runId).as(r)
      case Left(err) => logWorkflowFailed(runId) *> IO.raiseError(err)
    }
  } yield result
}

trait WorkflowTracer {
  import Workflow._

  def logStepStarted(step: Step[_]): IO[Unit]

  def logStepCompleted[A](step: Step[A], result: A): IO[Unit]

  def logStepFailed(step: Step[_], error: Throwable): IO[Unit]

  def logStepCompensationStarted(step: Step[_]): IO[Unit]

  def logStepCompensationFailed(step: Step[_], error: Throwable): IO[Unit]

  def logStepCompensationCompleted(step: Step[_]): IO[Unit]
}

case class InMemoryWorkflowStore(store: Ref[IO, Vector[WorkflowEvent]])
    extends WorkflowStore {

  case class InMemoryWorkflowTracer(runId: UUID) extends WorkflowTracer {
    override def logStepStarted(step: Workflow.Step[_]): IO[Unit] =
      store
        .getAndUpdate(v => v.appended(WorkflowStepStarted(step.name, runId)))
        .as(())

    override def logStepCompleted[A](
        step: Workflow.Step[A],
        result: A
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowStepCompleted(step.name, runId, result.toString))
        )
        .as(())

    override def logStepFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowStepFailed(step.name, runId, error))
        )
        .as(())

    override def logStepCompensationStarted(step: Workflow.Step[_]): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationStarted(step.name, runId))
        )
        .as(())

    override def logStepCompensationFailed(
        step: Workflow.Step[_],
        error: Throwable
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationFailed(step.name, runId, error))
        )
        .as(())

    override def logStepCompensationCompleted(
        step: Workflow.Step[_]
    ): IO[Unit] =
      store
        .getAndUpdate(v =>
          v.appended(WorkflowCompensationCompleted(step.name, runId))
        )
        .as(())

  }

  override def logWorkflowStarted(
      name: String,
      runId: ju.UUID
  ): IO[WorkflowTracer] = for {
    _ <- store.getAndUpdate(v => v.appended(WorkflowStarted(name, runId)))
    tracer = new InMemoryWorkflowTracer(runId)
  } yield tracer

  override def logWorkflowCompleted(runId: ju.UUID): IO[Unit] =
    store.getAndUpdate(v => v.appended(WorkflowCompleted(runId))).as(())

  override def logWorkflowFailed(runId: ju.UUID): IO[Unit] =
    store.getAndUpdate(v => v.appended(WorkflowFailed(runId))).as(())
}

object WorkflowRuntime {
  import Workflow._
  type RollbackRef = Ref[IO, IO[Unit]]

  private class Run(store: WorkflowStore, rollback: RollbackRef) {

    private def tellIO(
        tracer: WorkflowTracer,
        step: Step[_]
    ): IO[Unit] = {

      val rollbackStep = for {
        _ <- tracer.logStepCompensationStarted(step)
        either <- step.compensate.attempt
        _ <- either match {
          case Right(_)  => tracer.logStepCompensationCompleted(step)
          case Left(err) => tracer.logStepCompensationFailed(step, err)
        }
      } yield ()

      rollback.modify(steps => (rollbackStep *> steps, ()))
    }

    private val runCompensation: IO[Unit] = rollback.get.flatten

    private def foldIO(
        tracer: WorkflowTracer
    )(implicit cs: ContextShift[IO]): FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step @ Step(_, _, _) => processStep(tracer, step)
          case FromSeq(seq)         => seq.foldMap(foldIO(tracer))
          case FromPar(par) => {
            val parIO = par
              .foldMap(foldIO(tracer).andThen(IO.ioParallel.parallel))
            IO.ioParallel.sequential(parIO)
          }
        }
      }

    private def processStep[A](tracer: WorkflowTracer, step: Step[A]): IO[A] = {
      tracer.logStepStarted(step) *> step.effect.attempt.flatMap {
        case Right(a) =>
          tracer.logStepCompleted(step, a) *> tellIO(tracer, step).as(a)
        case Left(err) =>
          tracer.logStepFailed(step, err) *> runCompensation *> IO
            .raiseError(
              err
            )
      }
    }

    def toIO[A](workflow: Workflow[A])(implicit cs: ContextShift[IO]): IO[A] =
      store.logWorkflowExecution { tracer =>
        workflow.foldMap(foldIO(tracer))
      }
  }

  def run[A](
      store: WorkflowStore
  )(workflow: Workflow[A])(implicit cs: ContextShift[IO]): IO[A] = for {
    ref <- Ref.of[IO, IO[Unit]](IO.unit)
    result <- new Run(store, ref).toIO(workflow)
  } yield result
}

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

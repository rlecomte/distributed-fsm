package io.rlecomte.fsm

import io.rlecomte.fsm.store.InMemoryEventStore
import munit.ScalaCheckEffectSuite
import munit.CatsEffectSuite
import org.scalacheck.effect.PropF
import cats.effect.kernel.Resource
import io.rlecomte.fsm._
import io.rlecomte.fsm.test._
import cats.implicits._
import cats.effect.IO

class WorkflowResumeSpec extends CatsEffectSuite with ScalaCheckEffectSuite {

  case class CtxF[A](next: Event => Either[String, A])
  case class Fix[F[_]](unfix: F[Fix[F]])
  type Ctx = Fix[CtxF]

  def errorMsg(state: String, event: WorkflowEvent): String =
    s"[$state] don't accept event [$event]"

  val initialState: Ctx = Fix {
    CtxF { event =>
      event.payload match {
        case WorkflowStarted(_, _) => Right(startedState)
        case s                     => Left(errorMsg("initial state", s))
      }
    }
  }

  val startedState: Ctx = Fix {
    CtxF { event =>
      event.payload match {
        case WorkflowFailed      => Right(stoppedState)
        case WorkflowCompleted   => Right(stoppedState)
        case StepStarted(_, _)   => Right(startedState)
        case StepCompleted(_, _) => Right(startedState)
        case StepFailed(_, _)    => Right(startedState)
        case ParStarted(_)       => Right(startedState)
        case SeqStarted(_)       => Right(startedState)
        case s                   => Left(errorMsg("started state", s))
      }
    }
  }

  val stoppedState: Ctx = Fix {
    CtxF { event =>
      event.payload match {
        case WorkflowStarted(_, _) => Right(startedState)
        case CompensationStarted   => Right(startedCompensation)
        case s                     => Left(errorMsg("stopped state", s))
      }
    }
  }

  val startedCompensation: Ctx = Fix {
    CtxF { event =>
      event.payload match {
        case StepCompensationStarted(_)   => Right(startedCompensation)
        case StepCompensationFailed(_, _) => Right(startedCompensation)
        case StepCompensationCompleted(_) => Right(startedCompensation)
        case CompensationFailed           => Right(stoppedCompensation)
        case CompensationCompleted        => Right(stoppedCompensation)
        case s                            => Left(errorMsg("started compensation", s))
      }
    }
  }

  val stoppedCompensation: Ctx = Fix {
    CtxF { s => Left(errorMsg("stopped compensation", s.payload)) }
  }

  val storeResource = ResourceFixture(Resource.eval(InMemoryEventStore.newStore))

  storeResource.test("Check random execution plan") { implicit store =>
    PropF.forAllF { (w: WorkflowExecutionPlan) =>
      for {
        _ <- IO(println("============ Execute plan ============"))
        executedActions <- w.value
        events <- store.readEvents(executedActions.runId)
        result = events.foldM[Either[String, *], Ctx](initialState) { case (f, evt) =>
          f.unfix.next(evt)
        }
      } yield assert(result.isRight, result)
    }
  }
}

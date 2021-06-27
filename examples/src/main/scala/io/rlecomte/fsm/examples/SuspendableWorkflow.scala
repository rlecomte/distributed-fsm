package io.rlecomte.fsm.examples

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import io.circe.syntax._
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.runtime.ProcessSucceeded
import io.rlecomte.fsm.runtime.WorkflowRuntime
import io.rlecomte.fsm.store.InMemoryEventStore

object SuspendableWorkflow extends IOApp {

  import io.rlecomte.fsm.Workflow._

  case class PersonnalInformation(firstname: String, lastname: String, phone: String)

  object PersonnalInformation {
    import io.circe._, io.circe.generic.semiauto._
    implicit val personnalInformationCodec: Codec[PersonnalInformation] = deriveCodec
  }

  val askFirstnameAndWait = for {
    _ <- step("ask firstname", IO.consoleForIO.println("Enter your firstname  :"))
    firstname <- asyncStep[String]("wait for firstname", "firstname")
  } yield firstname

  val askLastnameAndWait = for {
    _ <- step("ask lastname", IO.consoleForIO.println("Enter your lastname  :"))
    lastname <- asyncStep[String]("wait for lastname", "lastname")
  } yield lastname

  val askPhoneAndWait = for {
    _ <- step("ask phone number", IO.consoleForIO.println("Enter your phone  :"))
    phone <- asyncStep[String]("wait for phone", "phone")
  } yield phone

  val program = FSM.define_[PersonnalInformation]("Ask personnal information") {
    (askFirstnameAndWait, askLastnameAndWait, askPhoneAndWait).mapN(PersonnalInformation.apply)
  }

  val getDataFromStdin = for {
    store <- InMemoryEventStore.newStore
    runId <- WorkflowRuntime.start(store, program, ()).flatMap(r => r.join.as(r.runId))
    _ <- IO.consoleForIO.readLine
      .flatMap(firstname =>
        WorkflowRuntime.feedAndResume(store, runId, "firstname", firstname.asJson, program)
      )
      .flatMap(_.traverse(_.join))
    _ <- IO.consoleForIO.readLine
      .flatMap(lastname =>
        WorkflowRuntime.feedAndResume(store, runId, "lastname", lastname.asJson, program)
      )
      .flatMap(_.traverse(_.join))
    r <- IO.consoleForIO.readLine
      .flatMap(phone => WorkflowRuntime.feedAndResume(store, runId, "phone", phone.asJson, program))

    info <- r match {
      case Right(fib) =>
        fib.join.flatMap {
          case ProcessSucceeded(value) => IO.pure(value)
          case _                       => IO.raiseError(new RuntimeException("Data should be return."))

        }
      case _ => IO.raiseError(new RuntimeException("Data should be return."))
    }
  } yield info

  override def run(args: List[String]): IO[ExitCode] = for {
    data <- getDataFromStdin
    _ <- IO.consoleForIO.println(s"Result : $data")
  } yield ExitCode.Success
}

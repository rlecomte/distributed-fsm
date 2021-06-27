package io.rlecomte.fsm.examples

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import io.circe.syntax._
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.runtime.ProcessSucceeded
import io.rlecomte.fsm.store.EventStore
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

  def getDataFromStdin(store: EventStore) = for {
    runId <- program.startEmpty(store).flatMap(r => r.join.as(r.runId))

    _ <- IO.consoleForIO.readLine
      .map(_.asJson)
      .flatMap(program.feedAndResumeOrFail(store, runId, "firstname"))
      .flatMap(_.join)

    _ <- IO.consoleForIO.readLine
      .map(_.asJson)
      .flatMap(program.feedAndResumeOrFail(store, runId, "lastname"))
      .flatMap(_.join)

    r <- IO.consoleForIO.readLine
      .map(_.asJson)
      .flatMap(program.feedAndResumeOrFail(store, runId, "phone"))
      .flatMap(_.join)

    info <- r match {
      case ProcessSucceeded(value) => IO.pure(value)
      case _                       => IO.raiseError(new RuntimeException("Data should be return."))
    }
  } yield info

  def builtInData(store: EventStore) = for {
    runId <- program.startEmpty(store).flatMap(r => r.join.as(r.runId))

    _ <- (
      program.feed(store, runId, "firstname")("Romain".asJson),
      program.feed(store, runId, "lastname")("Lecomte".asJson),
      program.feed(store, runId, "phone")("000000000".asJson)
    ).tupled

    r <- program.resumeOrFail(store, runId).flatMap(_.join)

    info <- r match {
      case ProcessSucceeded(value) => IO.pure(value)
      case v                       => IO.raiseError(new RuntimeException(s"Data should be return. $v"))
    }
  } yield info

  override def run(args: List[String]): IO[ExitCode] = for {
    store <- InMemoryEventStore.newStore
    //data <- getDataFromStdin(store)
    //_ <- IO.consoleForIO.println(s"Stdin Result : $data")
    dataBuiltIn <- builtInData(store)
    _ <- IO.consoleForIO.println(s"Builtin Result : $dataBuiltIn")
  } yield ExitCode.Success
}

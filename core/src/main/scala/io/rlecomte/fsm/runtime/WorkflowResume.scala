package io.rlecomte.fsm.runtime

import io.circe.Json
import io.rlecomte.fsm.FSM
import cats.arrow.FunctionK
import io.rlecomte.fsm.Workflow._
import io.circe.Decoder
import io.rlecomte.fsm.store._
import io.rlecomte.fsm.RunId
import cats.effect.IO
import cats.data.EitherT

case class WorkflowResume[I, O](version: Version, input: I, workflow: Workflow[O])

object WorkflowResume {

  def resume[I, O](
      runId: RunId,
      store: EventStore,
      fsm: FSM[I, O]
  )(implicit decoder: Decoder[I]): IO[Either[StateError, WorkflowResume[I, O]]] = (for {
    state <- EitherT(
      WorkflowState.loadState(store, runId).map(_.toRight(CantResumeState))
    )
    (input, workflow) <- state.status match {
      case WorkflowState.Completed | WorkflowState.Failed =>
        EitherT.fromEither[IO](resumeFromState(fsm, state.input, state.successfulSteps))
      case _ =>
        EitherT[IO, StateError, (I, Workflow[O])](IO.pure(Left(CantResumeState)))
    }
  } yield WorkflowResume[I, O](state.seqNum, input, workflow)).value

  def resumeFromState[I, O](
      fsm: FSM[I, O],
      input: Json,
      alreadyProcessedStep: Map[String, Json]
  )(implicit decoder: Decoder[I]): Either[StateError, (I, Workflow[O])] = for {
    input <- decoder.decodeJson(input).left.map(err => CantDecodePayload(err.message))
    workflow = fsm.workflow(input).compile(resumeWorkflow(alreadyProcessedStep))
  } yield (input, workflow)

  def resumeWorkflow(alreadyProcessedStep: Map[String, Json]): FunctionK[WorkflowOp, WorkflowOp] =
    new FunctionK[WorkflowOp, WorkflowOp] {
      override def apply[A](fa: WorkflowOp[A]): WorkflowOp[A] = fa match {
        case step @ Step(name, _, compensate, _, _, decoder) =>
          alreadyProcessedStep
            .get(name)
            .flatMap(input => decoder.decodeJson(input).toOption)
            .map(result => AlreadyProcessedStep(name, result, compensate))
            .getOrElse(step)

        case FromSeq(op) => FromSeq(op.compile(resumeWorkflow(alreadyProcessedStep)))

        case FromPar(op) => FromPar(op.compile(resumeWorkflow(alreadyProcessedStep)))

        case other => other
      }
    }
}

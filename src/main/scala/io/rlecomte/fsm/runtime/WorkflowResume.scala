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
      case WorkflowState.Completed | WorkflowState.Failed | WorkflowState.Suspended =>
        EitherT.fromEither[IO](resumeFromState(fsm, state.input, state))
      case _ =>
        EitherT[IO, StateError, (I, Workflow[O])](IO.pure(Left(CantResumeState)))
    }
  } yield WorkflowResume[I, O](state.seqNum, input, workflow)).value

  def resumeFromState[I, O](
      fsm: FSM[I, O],
      input: Json,
      state: WorkflowState
  )(implicit decoder: Decoder[I]): Either[StateError, (I, Workflow[O])] = for {
    input <- decoder.decodeJson(input).left.map(err => CantDecodePayload(err.message))
    workflow = fsm.workflow(input).compile(resumeWorkflow(state))
  } yield (input, workflow)

  def resumeWorkflow(state: WorkflowState): FunctionK[WorkflowOp, WorkflowOp] =
    new FunctionK[WorkflowOp, WorkflowOp] {
      override def apply[A](fa: WorkflowOp[A]): WorkflowOp[A] = fa match {
        case step @ Step(name, _, compensate, _, _, decoder) =>
          state.successfulSteps
            .get(name)
            .flatMap(input => decoder.decodeJson(input).toOption)
            .map(result => AlreadyProcessedStep(name, result, compensate))
            .getOrElse(step)

        //case step @ AsyncStep(name, _, waitFor, _, decoder) =>
        //  state.successfulSteps
        //    .get(name)
        //    .flatMap(input => decoder.decodeJson(input).toOption)
        //    .map(result => AlreadyProcessedStep(name, result, IO.unit))
        //    .getOrElse(step)

        case FromSeq(op) => FromSeq(op.compile(resumeWorkflow(state)))

        case FromPar(op) => FromPar(op.compile(resumeWorkflow(state)))

        case other => other
      }
    }
}

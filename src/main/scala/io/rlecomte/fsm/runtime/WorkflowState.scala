package io.rlecomte.fsm.runtime

import io.rlecomte.fsm.RunId
import io.circe.Json
import io.rlecomte.fsm.store._
import cats.effect.IO
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.WorkflowCompleted
import io.rlecomte.fsm.WorkflowStarted
import io.rlecomte.fsm.WorkflowFailed
import io.rlecomte.fsm.StepFailed
import io.rlecomte.fsm.StepStarted
import io.rlecomte.fsm.StepCompleted
import io.rlecomte.fsm.StepCompensationCompleted
import io.rlecomte.fsm.StepCompensationStarted
import io.rlecomte.fsm.StepCompensationFailed
import io.rlecomte.fsm.CompensationStarted
import io.rlecomte.fsm.CompensationCompleted
import io.rlecomte.fsm.CompensationFailed
import io.rlecomte.fsm.ParStarted
import io.rlecomte.fsm.SeqStarted

case class WorkflowState(
    runId: RunId,
    seqNum: SeqNum,
    input: Json,
    successfulSteps: Map[String, Json],
    failedSteps: Set[String],
    compensatedSteps: Set[String],
    failedCompensationSteps: Set[String],
    status: WorkflowState.StateStatus
)

object WorkflowState {

  sealed trait StateStatus
  case object Started extends StateStatus
  case object Completed extends StateStatus
  case object Failed extends StateStatus
  case object StateCompensationStarted extends StateStatus
  case object StateCompensationFailed extends StateStatus
  case object StateCompensationCompleted extends StateStatus

  def loadState(store: EventStore, runId: RunId): IO[Option[WorkflowState]] = {
    store.readEvents(runId).map(_.foldLeft(Option.empty[WorkflowState])(hydrateState))
  }

  def hydrateState(state: Option[WorkflowState], event: Event): Option[WorkflowState] =
    (event.payload, state) match {
      case (WorkflowStarted(_, input), _) =>
        Some(
          WorkflowState(
            event.runId,
            event.seqNum,
            input,
            Map.empty,
            Set.empty,
            Set.empty,
            Set.empty,
            Started
          )
        )

      case (WorkflowCompleted, Some(state)) =>
        Some(state.copy(status = Completed))

      case (WorkflowFailed, Some(state)) =>
        Some(state.copy(status = Failed))

      case (StepStarted(_, _), Some(s)) =>
        Some(s)

      case (StepCompleted(step, payload), Some(s)) =>
        Some(s.copy(successfulSteps = s.successfulSteps + ((step, payload))))

      case (StepFailed(step, _), Some(s)) =>
        Some(s.copy(failedSteps = s.failedSteps + step))

      case (StepCompensationStarted(_), Some(s)) =>
        Some(s)

      case (StepCompensationCompleted(step), Some(s)) =>
        Some(s.copy(compensatedSteps = s.compensatedSteps + step))

      case (StepCompensationFailed(step, _), Some(s)) =>
        Some(s.copy(failedCompensationSteps = s.failedCompensationSteps + step))

      case (CompensationStarted, Some(s)) =>
        Some(s.copy(status = StateCompensationStarted))

      case (CompensationCompleted, Some(s)) =>
        Some(s.copy(status = StateCompensationCompleted))

      case (CompensationFailed, Some(s)) =>
        Some(s.copy(status = StateCompensationFailed))

      case (SeqStarted(_), s) => s
      case (ParStarted(_), s) => s
      case (_, _)             => None
    }
}

package io.rlecomte.fsm

import cats.effect.IO
import io.rlecomte.fsm.Projection._

class Projection(backend: BackendEventStore) {

  val getSummary: IO[SummaryProjection] = for {
    events <- backend.readAllEvents
    summary = events.foldLeft(SummaryProjection(Nil))(
      SummaryProjection.buildSummary
    )
  } yield summary

  def getJobDetail(runId: RunId): IO[JobDetailProjection] = for {
    events <- backend.readEvents(runId)
    detail = events
      .foldLeft(Option.empty[JobDetailProjection])(
        JobDetailProjection.buildDetail
      )
      .get
  } yield detail
}

object Projection {

  case class SummaryProjection(jobs: List[(String, RunId)])

  object SummaryProjection {

    def buildSummary(
        state: SummaryProjection,
        event: Event
    ): SummaryProjection = event.payload match {
      case WorkflowStarted(name) =>
        SummaryProjection((name, event.runId) :: state.jobs)
      case _ => state
    }
  }

  sealed trait JobStatus

  object JobStatus {
    case object Pending extends JobStatus
    case object Completed extends JobStatus
    case object Failed extends JobStatus
  }

  sealed trait StepStatus

  object StepStatus {
    case object Started extends StepStatus
    case object Finished extends StepStatus
    case object Failed extends StepStatus
    case object Compensated extends StepStatus
  }

  case class DotSchema(
      relations: Map[String, List[String]],
      status: Map[String, StepStatus]
  )

  case class JobDetailProjection(
      fsm: String,
      id: RunId,
      status: JobStatus,
      dot: DotSchema
  )

  object JobDetailProjection {

    def buildDetail(
        state: Option[JobDetailProjection],
        event: Event
    ): Option[JobDetailProjection] = event.payload match {
      case WorkflowStarted(workflowName) =>
        Some(
          JobDetailProjection(
            workflowName,
            event.runId,
            JobStatus.Pending,
            DotSchema(Map.empty, Map.empty)
          )
        )
      case WorkflowCompleted =>
        state.map(job => job.copy(status = JobStatus.Completed))
      case WorkflowFailed =>
        state.map(job => job.copy(status = JobStatus.Failed))
      //TODO deal with other event
      case _ => state
    }
  }
}

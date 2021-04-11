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

  def getJobDetail(runId: RunId): IO[String] = for {
    events <- backend.readEvents(runId)
    detail = events
      .foldLeft(Option.empty[State])(
        mk
      )
      .get
      .dot
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

  //case class Crumb(
  //    parentGraph: Option[Graph],
  //    lastStep: Option[String],
  //    relations: Map[String, List[String]],
  //    status: Map[String, StepStatus]
  //)

  //case class GraphLevel(
  //    parentGraphRef: List[EventId],
  //    relations: Map[String, List[String]] = Map.empty,
  //    lastStepOpt: Option[String] = None
  //) {

  //  def push(step: String): GraphLevel = {
  //    val newRelations = lastStepOpt match {
  //      case Some(lastStep) =>
  //        relations.updatedWith(lastStep) { toStep =>
  //          Some(step :: toStep.getOrElse(Nil))
  //        }
  //      case None => relations
  //    }
  //    this.copy(relations = newRelations, lastStepOpt = Some(step))
  //  }

  //  def dot: String = {
  //    relations
  //      .flatMap { case (k, v) =>
  //        v.map(vv => s"$k -> $vv")
  //      }
  //      .toList
  //      .mkString("graph foo { ", "\n", " }")
  //  }
  //}

  //case class JobDetailProjection(
  //    fsm: String,
  //    id: RunId,
  //    status: JobStatus,
  //    dot: DotSchema
  //)

  //case class State(
  //    map: Map[EventId, GraphLevel],
  //    stack: List[EventId],
  //    lastCompletedSteps: List[EventId]
  //) {
  //  def pushLevel(id: EventId, correlationId: EventId): State =
  //    copy(map = map + (id -> GraphLevel(parentGraphRef = correlationId)), stack = id :: stack)

  //  def stepStart(step: String, correlationId: EventId): State = {
  //    copy(map = map.updatedWith(correlationId) { level => level.map(_.push(step)) })
  //  }

  //  def reduceGraph: GraphLevel = stack
  //    .foldLeft(map) { (m, eventId) =>
  //      val level = m.get(eventId).get
  //      m.get(level.parentGraphRef) match {
  //        case Some(parentLevel) => {
  //          val newParentLevel =
  //            parentLevel.copy(relations = parentLevel.relations ++ level.relations)
  //          m + (level.parentGraphRef -> newParentLevel) - eventId
  //        }
  //        case None => m
  //      }
  //    }
  //    .toList
  //    .map(_._2)
  //    .head
  //}

  //object JobDetailProjection {
  //  def buildDetail(
  //      state: State,
  //      event: Event
  //  ): State = event.payload match {
  //    case WorkflowStarted(_) =>
  //      State(map = Map.empty, List(event.id))

  //    case SeqStarted(correlationId) =>
  //      state.pushLevel(event.id, correlationId)

  //    case StepStarted(step, correlationId) =>
  //      state.stepStart(step, correlationId)

  //    case _ => state
  //  }
  //}

  case class Relations(steps: List[List[String]])

  sealed trait Op {
    def inOutStep: (List[String], List[String])

    def relations: List[(String, String)]

    def update(correlationId: EventId, op: Op): Op
  }
  case class Step(name: String) extends Op {
    override def inOutStep: (List[String], List[String]) = (List(name), List(name))

    override def relations: List[(String, String)] = Nil

    override def update(correlationId: EventId, op: Op): Op = this
  }
  case class Seq(id: EventId, value: List[Op]) extends Op {
    override def inOutStep: (List[String], List[String]) =
      (
        value.lastOption.toList.flatMap(_.inOutStep._2),
        value.headOption.toList.flatMap(_.inOutStep._1)
      )

    override def relations: List[(String, String)] =
      value
        .foldRight((Option.empty[List[String]], List[(String, String)]())) {
          case (op, (previousStep, relations)) =>
            val (in, out) = op.inOutStep
            val updatedRelations = previousStep match {
              case Some(previousSteps) =>
                relations ::: previousSteps.flatMap(s => in.map(ss => (s, ss))) ::: op.relations
              case None => relations ::: op.relations
            }

            (Some(out), updatedRelations)
        }
        ._2

    override def update(correlationId: EventId, op: Op): Op = {
      if (id == correlationId) {
        copy(id, value = op :: value)
      } else {
        copy(id, value = value.map(_.update(correlationId, op)))
      }
    }
  }
  case class Par(id: EventId, value: List[Op]) extends Op {
    override def inOutStep: (List[String], List[String]) = {
      val in = value.flatMap { subOp =>
        subOp.inOutStep._1
      }

      val out = value.flatMap { subOp =>
        subOp.inOutStep._2
      }

      (in, out)
    }

    def relations: List[(String, String)] = value.flatMap(_.relations)

    override def update(correlationId: EventId, op: Op): Op = {
      if (id == correlationId) {
        copy(id, value = op :: value)
      } else {
        copy(id, value = value.map(_.update(correlationId, op)))
      }
    }
  }

  case class State(entryPoint: Op) {
    def dot: String = {
      entryPoint.relations
        .map { case (k, v) =>
          s""""$k" -> "$v";"""
        }
        .toSet
        .mkString("digraph Foo { \n", "\n", "\n }")
    }
  }

  def mk(state: Option[State], event: Event): Option[State] = event.payload match {
    case WorkflowStarted(_) => Some(State(Seq(event.id, Nil)))
    case SeqStarted(correlationId) =>
      state.map(_.entryPoint.update(correlationId, Seq(event.id, Nil))).map(State.apply)
    case ParStarted(correlationId) =>
      state.map(_.entryPoint.update(correlationId, Par(event.id, Nil))).map(State.apply)
    case StepStarted(step, correlationId) =>
      state.map(_.entryPoint.update(correlationId, Step(step))).map(State.apply)
    case _ => state
  }
}

package io.rlecomte.fsm.store

import cats.effect.IO
import cats.effect.kernel.Ref
import io.rlecomte.fsm.Event
import io.rlecomte.fsm.RunId
import java.util.UUID
import java.time.Instant
import io.rlecomte.fsm.WorkflowEvent
import io.rlecomte.fsm.EventId
import cats.effect.std.Queue

case class InMemoryEventStore(
    ref: Ref[IO, Vector[Event]],
    refsVersions: Queue[IO, Map[RunId, Version]]
) extends EventStore {

  override def unsafeRegisterEvent(
      runId: RunId,
      event: WorkflowEvent
  ): IO[EventId] = {
    unsafeTakeVersion(runId).bracket(v => recordMsg(runId, event, v._1))(v =>
      releaseVersion(runId, v._1, v._2)
    )
  }

  override def registerEvent(
      runId: RunId,
      event: WorkflowEvent,
      expectedVersion: Version
  ): IO[Either[VersionConflict, EventId]] = {
    takeVersion(runId, expectedVersion)
      .bracket(v => recordMsg(runId, event, v._1))(v => releaseVersion(runId, v._1, v._2))
      .redeemWith(catchConflictError, v => IO.pure(Right(v)))
  }

  override def readAllEvents: IO[List[Event]] = ref.get.map(_.toList)

  override def readEvents(runId: RunId): IO[List[Event]] =
    readAllEvents.map(_.filter(_.runId == runId))

  private def catchConflictError(err: Throwable): IO[Either[VersionConflict, EventId]] = err match {
    case vc @ VersionConflict(_, _) => IO.pure(Left(vc))
    case err                        => IO.raiseError(err)
  }

  private def recordMsg(runId: RunId, event: WorkflowEvent, currentVersion: Version) = {
    for {
      evtId <- IO(EventId(UUID.randomUUID()))
      inst <- IO(Instant.now())
      evt = Event(evtId, runId, Version.inc(currentVersion), inst, event)
      _ <- ref.getAndUpdate(vec => vec.appended(evt))
    } yield evtId
  }

  private def takeVersion(
      runId: RunId,
      expectedVersion: Version
  ): IO[(Version, Map[RunId, Version])] = {
    unsafeTakeVersion(runId: RunId)
      .flatMap { case i @ (currentVersion, _) =>
        if (currentVersion == expectedVersion) IO.pure(i)
        else IO.raiseError(VersionConflict(expectedVersion, currentVersion))
      }
  }

  private def unsafeTakeVersion(runId: RunId): IO[(Version, Map[RunId, Version])] = {
    refsVersions.take
      .map(versions => (versions.getOrElse(runId, EmptyVersion), versions))
  }

  private def releaseVersion(
      runId: RunId,
      currentVersion: Version,
      versions: Map[RunId, Version]
  ) = {
    refsVersions.offer(versions.updated(runId, Version.inc(currentVersion)))
  }
}

object InMemoryEventStore {
  val newStore: IO[InMemoryEventStore] = for {
    refStore <- Ref.of[IO, Vector[Event]](Vector())
    refVersions <- Queue.bounded[IO, Map[RunId, Version]](1)
  } yield InMemoryEventStore(refStore, refVersions)
}

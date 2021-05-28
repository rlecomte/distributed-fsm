package io.rlecomte.fsm

import cats.effect.IO

import java.util.UUID

case class RunId(value: UUID) extends AnyVal

object RunId {
  val newRunId: IO[RunId] = IO(UUID.randomUUID()).map(RunId.apply)
}

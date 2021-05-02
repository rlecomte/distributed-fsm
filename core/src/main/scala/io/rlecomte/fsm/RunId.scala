package io.rlecomte.fsm

import java.util.UUID
import cats.effect.IO

case class RunId(value: UUID) extends AnyVal

object RunId {
  val newRunId: IO[RunId] = IO(UUID.randomUUID()).map(RunId.apply)
}

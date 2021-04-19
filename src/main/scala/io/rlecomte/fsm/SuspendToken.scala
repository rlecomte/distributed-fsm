package io.rlecomte.fsm

import java.util.UUID
import cats.effect.IO

case class SuspendToken(runId: RunId, id: UUID)

object SuspendToken {
  def newToken: IO[SuspendToken] = for {
    runId <- RunId.newRunId
    id <- IO(UUID.randomUUID())
  } yield SuspendToken(runId, id)
}

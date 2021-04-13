package io.rlecomte.fsm

import Workflow._
import cats.effect.IO
import io.circe.{Decoder, Encoder}

case class FSM[I, O](name: String, workflow: I => Workflow[O]) {

  def compile()(implicit backend: BackendEventStore, encoder: Encoder[I]):
    CompiledFSM[I, O] = WorkflowRuntime.compile(backend, this)

  def retry(
             runId: RunId,
             backend: BackendEventStore
           )(implicit encoder: Encoder[I], decoder: Decoder[I]):
  IO[RetryFSM[I, O]] = WorkflowRuntime.retryFromState(runId, backend, this)
}

case class CompiledFSM[I, O](run: I => IO[(RunId, Either[Throwable, O])]) extends AnyVal
case class RetryFSM[I, O](run: IO[(RunId, Either[Throwable, O])]) extends AnyVal

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O]): FSM[I, O] = FSM(name, f)
}

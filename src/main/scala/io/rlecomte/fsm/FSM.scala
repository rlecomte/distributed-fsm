package io.rlecomte.fsm

import Workflow._
import cats.effect.IO

case class FSM[I, O](name: String, workflow: I => Workflow[O]) {

  def compile(implicit
      backend: BackendEventStore
  ): CompiledFSM[I, O] = WorkflowRuntime.compile(backend, this)
}

case class CompiledFSM[I, O](run: I => IO[(RunId, Either[Throwable, O])]) extends AnyVal

case class ResumedFSM[I, O](run: IO[O], compensate: IO[Unit])

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O]): FSM[I, O] = FSM(name, f)
}

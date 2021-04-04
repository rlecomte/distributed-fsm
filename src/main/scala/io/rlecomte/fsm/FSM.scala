package io.rlecomte.fsm

import Workflow._
import cats.effect.IO
import cats.effect.ContextShift

case class FSM[I, O](name: String, f: I => Workflow[O]) {

  def compile(implicit
      cs: ContextShift[IO],
      logger: WorkflowLogger
  ): CompiledFSM[I, O] = {
    CompiledFSM(WorkflowRuntime.compile(logger, this))
  }
}

case class CompiledFSM[I, O](run: I => IO[O]) extends AnyVal

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O]): FSM[I, O] = FSM(name, f)
}

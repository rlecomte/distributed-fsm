package io.rlecomte.fsm

import Workflow._

case class FSM[I, O](name: String, f: I => Workflow[O])

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O]): FSM[I, O] = FSM(name, f)
}

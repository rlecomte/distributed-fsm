package io.rlecomte.fsm

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder

import Workflow._

case class FSM[I, O](name: String, workflow: I => Workflow[O], inputCodec: Codec[I])

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O])(implicit inputCodec: Codec[I]): FSM[I, O] = {
    FSM(name, f, inputCodec)
  }

  def define_[O](name: String)(workflow: Workflow[O]): FSM[Unit, O] = {
    FSM(name, _ => workflow, Codec.from(Decoder[Unit], Encoder[Unit]))
  }
}

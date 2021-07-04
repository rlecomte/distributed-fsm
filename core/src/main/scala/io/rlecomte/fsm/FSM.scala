package io.rlecomte.fsm

import cats.effect.IO
import cats.kernel.Monoid
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.rlecomte.fsm.runtime.WorkflowRuntime
import io.rlecomte.fsm.store.EventStore

import Workflow._

case class FSM[I, O](name: String, workflow: I => Workflow[O], inputCodec: Codec[I]) {

  def start(store: EventStore, input: I) = WorkflowRuntime.start(store, this, input)

  def startEmpty(store: EventStore)(implicit M: Monoid[I]) =
    WorkflowRuntime.start(store, this, M.empty)

  def feed(store: EventStore, runId: RunId, token: String)(payload: Json) =
    WorkflowRuntime.feed(store, runId, token, payload)

  def feedAndResume(store: EventStore, runId: RunId, token: String)(payload: Json) =
    WorkflowRuntime.feedAndResume(store, runId, token, payload, this)

  def feedAndResumeOrFail(store: EventStore, runId: RunId, token: String)(payload: Json) =
    feedAndResume(store, runId, token)(payload).flatMap(IO.fromEither)

  def resume(store: EventStore, runId: RunId) = WorkflowRuntime.resume(store, this, runId)

  def resumeOrFail(store: EventStore, runId: RunId) =
    WorkflowRuntime.resume(store, this, runId).flatMap(IO.fromEither)
}

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O])(implicit inputCodec: Codec[I]): FSM[I, O] = {
    FSM(name, f, inputCodec)
  }

  def define_[O](name: String)(workflow: Workflow[O]): FSM[Unit, O] = {
    FSM(name, _ => workflow, Codec.from(Decoder[Unit], Encoder[Unit]))
  }
}

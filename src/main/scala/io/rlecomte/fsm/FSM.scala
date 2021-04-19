package io.rlecomte.fsm

import Workflow._
import cats.effect.{IO, FiberIO}
import io.rlecomte.fsm.store.EventStore
import cats.effect.kernel.Outcome

case class FSM[I, O](name: String, workflow: I => Workflow[O]) {

  def compile(implicit
      backend: EventStore
  ): CompiledFSM[I, O] = WorkflowRuntime.compile(backend, this)
}

case class CompiledFSM[I, O](runAsync: I => IO[(RunId, FiberIO[WorkflowResult[O]])])
    extends AnyVal {
  def runSync(input: I): IO[(RunId, Outcome[IO, Throwable, WorkflowResult[O]])] = for {
    (runId, fiber) <- runAsync(input)
    outcome <- fiber.join
  } yield (runId, outcome)
}

case class ResumedFSM[I, O](run: IO[WorkflowResult[O]], compensate: IO[Unit])

object FSM {
  def define[I, O](name: String)(f: I => Workflow[O]): FSM[I, O] = FSM(name, f)
}

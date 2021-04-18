package io.rlecomte.fsm

case class WorkflowError(value: String) extends AnyVal

object WorkflowError {
  def fromThrowable(error: Throwable): WorkflowError = WorkflowError(
    error.toString()
  )
}

package io.rlecomte.fsm

sealed trait WorkflowResult[+A]
case class WorkflowFailure[A](err: Throwable) extends WorkflowResult[A]
case object WorkflowSuspended extends WorkflowResult[Nothing]
case class WorkflowDone[A](value: A) extends WorkflowResult[A]

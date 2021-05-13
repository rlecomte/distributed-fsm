package io.rlecomte.fsm

import cats.free.FreeApplicative
import io.circe.Decoder
import cats.arrow.FunctionK
import io.circe.Json

object WaitFor {

  type WaitFor[A] = FreeApplicative[WaitForOp, A]

  sealed trait WaitForOp[A]
  case class Pending[A](id: String, decoder: Decoder[A]) extends WaitForOp[A]
  case class Ready[A](id: String, value: A) extends WaitForOp[A]

  def apply[A](id: String)(implicit decoder: Decoder[A]): WaitFor[A] = {
    cats.free.FreeApplicative.lift(Pending(id, decoder))
  }

  def feed[A](waitFor: WaitFor[A], refId: String, value: Json): WaitFor[A] = {
    val f = new FunctionK[WaitForOp, WaitForOp] {
      def apply[A](fa: WaitForOp[A]): WaitForOp[A] = fa match {
        case p @ Pending(id, decoder) =>
          if (id == refId) decoder.decodeJson(value).map(Ready(id, _)).getOrElse(p)
          else p
        case r @ Ready(_, _) => r
      }
    }

    waitFor.compile(f)
  }

  def tryResult[A](waitFor: WaitFor[A]): Option[A] = {
    waitFor.foldMap(new FunctionK[WaitForOp, Option] {
      override def apply[A](fa: WaitForOp[A]): Option[A] = fa match {
        case Ready(_, v) => Some(v)
        case _           => None
      }
    })
  }
}

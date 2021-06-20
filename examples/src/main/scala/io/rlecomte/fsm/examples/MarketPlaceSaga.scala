package io.rlecomte.fsm.examples

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import io.circe.Decoder
import io.circe.Encoder
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.runtime.ProcessCancelled
import io.rlecomte.fsm.runtime.ProcessFailed
import io.rlecomte.fsm.runtime.ProcessSucceeded
import io.rlecomte.fsm.runtime.WorkflowRuntime
import io.rlecomte.fsm.store.InMemoryEventStore

import scala.util.Random

object MarketPlaceSaga extends IOApp {

  object PaymentService {

    case class PaymentId(value: Int)

    object PaymentId {
      implicit val paymentIdEncoder: Encoder[PaymentId] = Encoder.encodeInt.contramap(_.value)

      implicit val paymentIdDecoder: Decoder[PaymentId] = Decoder.decodeInt.map(PaymentId.apply)
    }

    def executePayment(): IO[PaymentId] = IO(println("execute payment")).map(_ => PaymentId(1))

    def cancelPayment(paymentId: PaymentId): IO[Unit] = IO(println(s"cancel payment $paymentId"))
  }

  object OrderService {
    import PaymentService.PaymentId

    case class OrderId(value: Int)

    object OrderId {
      implicit val orderIdEncoder: Encoder[OrderId] = Encoder.encodeInt.contramap(_.value)

      implicit val orderIdDecoder: Decoder[OrderId] = Decoder.decodeInt.map(OrderId.apply)
    }

    def createOrder(paymentId: PaymentId): IO[OrderId] =
      IO(println(s"create order with paymentId $paymentId")).map(_ => OrderId(1))

    def cancelOrder(orderId: OrderId): IO[Unit] = IO(println(s"cancel order $orderId"))

    def concludeOrder(id: OrderId): IO[Unit] = IO(println(s"conclude order $id"))
  }

  object StockService {
    import OrderService._

    def prepareOrder(id: OrderId): IO[Unit] = {
      IO(println(s"prepare order $id")).flatMap { _ =>
        IO(Random.nextBoolean()).flatMap {
          case false => IO.unit
          case true  => IO.raiseError(new RuntimeException("prepare order failed!"))
        }
      }
    }

    def cancelPreparation(id: OrderId): IO[Unit] = IO(println(s"cancel preparation for order $id"))
  }

  object DeliveryService {
    import OrderService._

    def deliverOrder(id: OrderId): IO[Unit] = IO(println(s"deliver order $id"))

    def cancelDelivery(id: OrderId): IO[Unit] = IO(println(s"cancel delivery for order $id"))
  }

  object MarketPlace {

    import io.rlecomte.fsm.Workflow._
    import OrderService._

    val marketPlaceWorkflow =
      FSM.define_[OrderId]("market place saga") {
        for {
          paymentId <- step(
            "payment",
            PaymentService.executePayment(),
            paymentId => PaymentService.cancelPayment(paymentId)
          )

          orderId <- step(
            "create order",
            OrderService.createOrder(paymentId),
            orderId => OrderService.cancelOrder(orderId)
          )

          _ <- step(
            "prepare order",
            StockService.prepareOrder(orderId),
            (_: Unit) => StockService.cancelPreparation(orderId)
          )

          _ <- step(
            "deliver order",
            DeliveryService.deliverOrder(orderId),
            (_: Unit) => DeliveryService.cancelDelivery(orderId)
          )

          _ <- step("conclude order", OrderService.concludeOrder(orderId))
        } yield orderId
      }
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    store <- InMemoryEventStore.newStore
    fib <- WorkflowRuntime.start(store, MarketPlace.marketPlaceWorkflow, ())
    result <- fib.join
    _ <- result match {
      case ProcessFailed(_) =>
        IO(println("order creation failed. Proceed to compensation : ")) *> WorkflowRuntime
          .compensate(store, MarketPlace.marketPlaceWorkflow, fib.runId)
      case ProcessCancelled          => IO.unit
      case ProcessSucceeded(orderId) => IO(println(s"Order $orderId created."))
    }
  } yield ExitCode.Success
}

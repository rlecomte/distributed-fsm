package io.rlecomte.fsm.examples

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import io.circe.Decoder
import io.circe.Encoder
import io.rlecomte.fsm.FSM
import io.rlecomte.fsm.runtime.WorkflowRuntime
import io.rlecomte.fsm.store.InMemoryEventStore

object MarketPlaceSaga extends IOApp {

  object PaymentService {

    def executePayment(): IO[Unit] = IO(println("execute payment"))

    def cancelPayment(): IO[Unit] = IO(println("cancel payment"))
  }

  object OrderService {

    case class OrderId(value: Int)

    object OrderId {
      implicit val orderIdEncoder: Encoder[OrderId] = Encoder.encodeInt.contramap(_.value)

      implicit val orderIdDecoder: Decoder[OrderId] = Decoder.decodeInt.map(OrderId.apply)
    }

    def createOrder(): IO[OrderId] = IO(println("create order")).map(_ => OrderId(1))

    def cancelOrder(): IO[Unit] = IO(println("cancel order"))

    def concludeOrder(id: OrderId): IO[Unit] = IO(println(s"conclude order $id"))
  }

  object StockService {
    import OrderService._

    def prepareOrder(id: OrderId): IO[Unit] = IO(println(s"prepare order $id"))

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
      FSM[Unit, OrderId](
        "market place saga",
        { _ =>
          for {
            _ <- step("payment", PaymentService.executePayment(), PaymentService.cancelPayment())

            orderId <- step("create order", OrderService.createOrder(), OrderService.cancelOrder())

            _ <- step(
              "book stock",
              StockService.prepareOrder(orderId),
              StockService.cancelPreparation(orderId)
            )

            _ <- step(
              "deliver order",
              DeliveryService.deliverOrder(orderId),
              DeliveryService.cancelDelivery(orderId)
            )

            _ <- step("conclude order", OrderService.concludeOrder(orderId))
          } yield orderId
        }
      )
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    store <- InMemoryEventStore.newStore
    result <- WorkflowRuntime.startSync(store, MarketPlace.marketPlaceWorkflow, ())
    _ <- IO(println(s"Order ${result._2} created."))
  } yield ExitCode.Success
}

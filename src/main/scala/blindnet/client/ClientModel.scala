package blindnet.client

import cats.effect._
import tsec.cipher.symmetric.jca._

case class RouterToConnect(id: String, ip: String, port: Int, g: Int, p: Int)
case class Router(id: String, key: SecretKey[AES128CTR])

trait CircuitState {
  def getCircId = this match {
    case AwaitingCreated(cId, _, _, _, _) => IO(cId)
    case OneHop(cId, _, _, _, _)          => IO(cId)
    case TwoHop(cId, _, _, _, _)          => IO(cId)
    case ThreeHop(cId, _, _, _)           => IO(cId)
    case _                                => IO.raiseError(new Throwable("Cant get circuit id from circuit state"))
  }
}
case class Invalid()     extends CircuitState
case class Initialized() extends CircuitState

case class Established(
  circuitId: Short,
  router1: RouterToConnect,
  router2: RouterToConnect,
  router3: RouterToConnect
) extends CircuitState

case class AwaitingCreated(
  circuitId: Short,
  x: Int,
  router1: RouterToConnect,
  router2: RouterToConnect,
  router3: RouterToConnect
) extends CircuitState

case class OneHop(
  circuitId: Short,
  x2: Int,
  router1: Router,
  router2: RouterToConnect,
  router3: RouterToConnect
) extends CircuitState

case class TwoHop(
  circuitId: Short,
  x3: Int,
  router1: Router,
  router2: Router,
  router3: RouterToConnect
) extends CircuitState

case class ThreeHop(
  circuitId: Short,
  router1: Router,
  router2: Router,
  router3: Router
) extends CircuitState

package blindnet

import blindnet.model._
import cats.effect._
import cats.effect.concurrent._

package object router {
  type RouterId  = String
  type CircuitId = Short

  case class RouterConnection(
    socket: MessageSocket[EncryptedCell, EncryptedCell],
    circuits: Ref[IO, Set[CircuitId]],
    mappings: Ref[IO, Map[CircuitId, CircuitId]]
  )
  type Connections = Map[RouterId, RouterConnection]
}

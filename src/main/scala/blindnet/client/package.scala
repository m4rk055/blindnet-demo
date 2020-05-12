package blindnet

import blindnet.model._
import cats.effect._
import cats.effect.concurrent._

package object client {
  type RouterId  = String
  type CircuitId = Short

  case class RouterConnection(
    socket: MessageSocket[EncryptedCell, EncryptedCell],
    circuits: Ref[IO, Map[CircuitId, CircuitState]]
  ) {
    // def updateCiruits(cId: CircuitId, cState: CircuitState) =
    //   RouterConnection(socket, circuits.updated(cId, cState))
  }
  type Connections = Map[RouterId, RouterConnection]

  case class CompleteCircuit(
    socket: MessageSocket[EncryptedCell, EncryptedCell],
    data: ThreeHop
  )
}

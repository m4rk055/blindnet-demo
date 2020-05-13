package blindnet.router

import blindnet.model.EncryptedCell
import blindnet.model.MessageSocket
import tsec.cipher.symmetric.jca._

case class RouterData(id: String, ip: String, port: Int)

case class AppData(sk: String, g: Int, p: Int)

case class NextHop(messageSocket: MessageSocket[EncryptedCell, EncryptedCell], cirId: Short)

trait RCircuitState {
  val key: SecretKey[AES128CTR]
}
case class KeyExchanged(key: SecretKey[AES128CTR])                   extends RCircuitState
case class AwaitingNextHop(key: SecretKey[AES128CTR], next: NextHop) extends RCircuitState
case class Complete(key: SecretKey[AES128CTR], next: NextHop)        extends RCircuitState

trait Direction
case object Front extends Direction
case object Back  extends Direction

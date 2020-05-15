package blindnet.msgpool

import io.circe.{ Decoder, Encoder }

case class CellData(
  to: String,
  data: Array[Byte]
)

object CellData {
  implicit val enc: Encoder[CellData] = io.circe.generic.semiauto.deriveEncoder[CellData]
  implicit val dec: Decoder[CellData] = io.circe.generic.semiauto.deriveDecoder[CellData]
}

package blindnet.model

import java.nio.ByteBuffer

import scala.util.Try

import cats.implicits._
import scodec.Attempt._
import scodec._
import scodec.bits._
import scodec.codecs._
import tsec.common._
import tsec.hashing._
import tsec.hashing.jca._

trait RelayCommand {
  def getId: Byte = this match {
    case _: EXTEND   => 1
    case _: EXTENDED => 2
    case _: BEGIN    => 3
    case CONNECTED   => 4
    case _: DATA     => 5
  }

  def getBytes: Either[Throwable, Array[Byte]] = this match {

    case EXTEND(hs, rId) =>
      Try(
        ByteBuffer.allocate(4).putInt(hs).array() ++
          ByteBuffer.allocate(2).put(rId.getBytes()).array() ++
          ByteBuffer.allocate(492).array()
      ).toEither

    case EXTENDED(hs, kh) =>
      Try(
        ByteBuffer.allocate(4).putInt(hs).array() ++
          ByteBuffer.allocate(20).put(kh).array() ++
          ByteBuffer.allocate(473).array()
      ).toEither

    case BEGIN(x) =>
      Try(
        ByteBuffer.allocate(6).put(x.getBytes()).array() ++
          ByteBuffer.allocate(492).array()
      ).toEither

    case CONNECTED =>
      Try(ByteBuffer.allocate(498).array()).toEither

    case DATA(to, toLen, msg) => {
      Try(
        ByteBuffer.allocate(1).put(toLen).array() ++
          ByteBuffer.allocate(toLen).put(to.getBytes()).array() ++
          msg
      ).toEither
    }
  }
}
case class EXTEND(handshake: Int, routerId: String) extends RelayCommand
case class EXTENDED(handshake: Int, keyHash: CryptoHash[SHA1]) extends RelayCommand {
  override def toString() = s"EXTENDED($handshake, ${keyHash.toHexString}"
}
case class BEGIN(x: String)                                extends RelayCommand
case object CONNECTED                                      extends RelayCommand
case class DATA(to: String, toLen: Byte, msg: Array[Byte]) extends RelayCommand

trait Command {
  def getId: Byte = this match {
    case _: CREATE  => 1
    case _: CREATED => 2
    case _: DESTROY => 3
    case _: RELAY   => 4
  }

  def getBytes: Either[Throwable, Array[Byte]] = this match {
    case CREATE(hs) =>
      Try(
        ByteBuffer.allocate(4).putInt(hs).array() ++
          ByteBuffer.allocate(495).array()
      ).toEither

    case CREATED(hs, kh) =>
      Try(
        ByteBuffer.allocate(4).putInt(hs).array() ++
          ByteBuffer.allocate(20).put(kh).array() ++
          ByteBuffer.allocate(453).array()
      ).toEither

    case DESTROY() =>
      Try(ByteBuffer.allocate(509).array()).toEither

    case r: RELAY =>
      r.relayBytes

    case _ => Right(ByteBuffer.allocate(512).array())
  }
}
case class CREATE(handshake: Int) extends Command
case class CREATED(handshake: Int, keyHash: CryptoHash[SHA1]) extends Command {
  override def toString() = s"CREATED($handshake, ${keyHash.toHexString}"
}
case class DESTROY() extends Command
case class RELAY(
  streamId: Short = 0,
  digest: Array[Byte] = Array.empty[Byte],
  len: Short = 0,
  cmd: RelayCommand
) extends Command {

  def relayBytes: Either[Throwable, Array[Byte]] = {

    val headBytes = Try(
      ByteBuffer.allocate(2).putShort(streamId).array() ++
        ByteBuffer.allocate(6).put(digest).array() ++
        ByteBuffer.allocate(2).putShort(len).array() ++
        ByteBuffer.allocate(1).put(cmd.getId).array()
    ).toEither

    for {
      hb <- headBytes
      db <- cmd.getBytes
    } yield hb ++ db
  }
}

case class Cell(
  cmd: Command,
  circuitId: Short
) {

  def getPayload = getBytes.map(_.drop(3))

  def getBytes: Either[Throwable, Array[Byte]] =
    for {
      cmdBytes <- cmd.getBytes
      headerBytes <- Try(
                      ByteBuffer.allocate(2).putShort(circuitId).array() ++
                        ByteBuffer.allocate(1).put(cmd.getId).array()
                    ).toEither
    } yield headerBytes ++ cmdBytes
}

case class EncryptedCell(
  circuitId: Short,
  command: Byte,
  payload: Array[Byte]
)

object EncryptedCell {

  val ecEnc: Encoder[EncryptedCell] = new Encoder[EncryptedCell] {

    def encode(value: EncryptedCell): Attempt[BitVector] =
      Attempt.fromTry(
        Try(
          ByteBuffer.allocate(2).putShort(value.circuitId).array() ++
            ByteBuffer.allocate(1).put(value.command).array() ++
            ByteBuffer.allocate(509).put(value.payload).array()
        ).map(bytes => BitVector(bytes))
      )

    def sizeBound: SizeBound = SizeBound(512, Some(512)) // what is this?
  }

  val ecDec: Decoder[EncryptedCell] = new Decoder[EncryptedCell] {
    def decode(bits: BitVector): Attempt[DecodeResult[EncryptedCell]] = {
      val arr = bits.toByteArray
      if (arr.length != 512) Failure(Err.General(s"expected 512 bytes, got ${arr.length}", Nil))
      else {
        Try {
          val cId     = arr.take(2).toShortUnsafe
          val cmd     = arr.drop(2).take(1).head
          val payload = arr.drop(3)

          EncryptedCell(cId, cmd, payload)
        }.fold(
          e => Failure(Err.General(s"error decoding cell - ${e}", Nil)),
          s => Successful(DecodeResult(s, BitVector.empty))
        )
      }
    }
  }
}

object Cell {

  def decodeCell(cId: Short, cmd: Byte, payload: Array[Byte]): Either[String, Cell] =
    Try(
      if (payload.length != 509) Left(s"expected payload 509 bytes, got ${payload.length}")
      else if (cmd == 1) {
        val hs = payload.take(4).toIntUnsafe
        Right(Cell(CREATE(hs), cId))
      } else if (cmd == 2) {
        val hs = payload.take(4).toIntUnsafe
        val kh = payload.drop(4).take(20)
        Right(Cell(CREATED(hs, CryptoHash(kh)), cId))
      } else if (cmd == 3) {
        Right(Cell(DESTROY(), cId))
      } else if (cmd == 4) {
        val sid  = payload.take(2).toShortUnsafe
        val dig  = payload.drop(2).take(6)
        val len  = payload.drop(8).take(2).toShortUnsafe
        val rCmd = payload.drop(10).take(1).head
        if (rCmd == 1) {
          val hs  = payload.drop(11).take(4).toIntUnsafe
          val rId = payload.drop(15).take(2).toAsciiString
          Right(Cell(RELAY(sid, dig, len, EXTEND(hs, rId)), cId))
        } else if (rCmd == 2) {
          val hs = payload.drop(11).take(4).toIntUnsafe
          val kh = payload.drop(15).take(20)
          Right(Cell(RELAY(sid, dig, len, EXTENDED(hs, CryptoHash(kh))), cId))
        } else if (rCmd == 5) {
          val toLen = payload.drop(11).take(1).head
          val to    = payload.drop(12).take(toLen).toAsciiString
          val msg   = payload.drop(12 + toLen)
          Right(Cell(RELAY(sid, dig, len, DATA(to, toLen, msg)), cId))
        } else
          Left(s"unknown relay command $rCmd")
      } else
        Left(s"unknown command $cmd")
    ).toEither.leftMap(_ => "error decoding cell").flatten

  val byteArr: Codec[Array[Byte]] = Codec(
    arr => Encoder.encodeSeq(byte)(arr.toIndexedSeq),
    buffer => Decoder.decodeCollect[Array, Byte](byte, None)(buffer)
  )
}

package blindnet.model

import cats.effect._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.tcp._
import scodec.stream.{ StreamDecoder, StreamEncoder }
import scodec.{ Decoder, Encoder }

trait MessageSocket[In, Out] {
  def read: Stream[IO, In]
  def write1(out: Out): IO[Unit]

  def close: IO[Unit]
}

object MessageSocket {

  def apply[In, Out](
    socket: Socket[IO],
    inDecoder: Decoder[In],
    outEncoder: Encoder[Out],
    outputBound: Int
  )(implicit cs: ContextShift[IO]): IO[MessageSocket[In, Out]] =
    for {
      outgoing <- Queue.bounded[IO, Out](outputBound)
    } yield new MessageSocket[In, Out] {
      def read: Stream[IO, In] = {
        val readSocket = socket
          .reads(512)
          .through(StreamDecoder.many(inDecoder).toPipeByte[IO])

        val writeOutput = outgoing.dequeue
          .through(StreamEncoder.many(outEncoder).toPipeByte)
          .through(socket.writes(None))

        readSocket.concurrently(writeOutput)
      }
      def write1(out: Out): IO[Unit] = outgoing.enqueue1(out)

      def close: IO[Unit] = socket.close
    }
}

object MessageSocketInstances {

  def encryptedCells(socket: Socket[IO])(implicit cs: ContextShift[IO]) =
    MessageSocket(
      socket,
      EncryptedCell.ecDec,
      EncryptedCell.ecEnc,
      128
    )
}

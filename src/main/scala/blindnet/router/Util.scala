package blindnet.router

import cats.effect._
import cats.effect.IO._
import cats.implicits._
import fs2._
import tsec.cipher.symmetric.jca._
import tsec.cipher.symmetric._

object Util {

  def cast[A <: RCircuitState](cs: RCircuitState): IO[A] =
    IO.fromTry(scala.util.Try(cs.asInstanceOf[A]))
      .handleErrorWith(_ => IO.raiseError(new Throwable(s"Invalid state")))

  implicit class EitherOps[E, A](either: Either[E, A]) {
    def toIO: IO[A] = IO.fromEither(either.leftMap(e => new Throwable(e.toString)))
  }

  def raiseErrorIO[A](msg: String): IO[A] = IO.raiseError[A](new Throwable(msg))
  def putLnIO(text: Any): IO[Unit]        = IO(println(text))

  def raiseErrorStream(msg: String) = Stream.raiseError[IO](new Throwable(msg))
  def putLnStream(text: Any)        = Stream.eval(IO(println(text)))

  def noIvCT(ct: Array[Byte]) = CipherText[AES128CTR](RawCipherText(ct), Iv(Array.fill(16)(0: Byte)))
}

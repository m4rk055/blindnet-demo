package blindnet.client

import cats.effect.IO._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import fs2._
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._

object Util {

  def cast[A <: CircuitState](cs: CircuitState): IO[A] =
    IO.fromTry(scala.util.Try(cs.asInstanceOf[A]))
      .handleErrorWith(_ => IO.raiseError(new Throwable(s"Invalid state")))

  implicit class EitherOps[E, A](either: Either[E, A]) {
    def toIO: IO[A] = IO.fromEither(either.leftMap(e => new Throwable(e.toString)))
  }

  implicit class GetState(cs: Ref[IO, Map[CircuitId, CircuitState]]) {

    def getState(id: CircuitId): IO[CircuitState] = cs.get.map(_.get(id)).flatMap {
      case None     => raiseErrorIO(s"Circuit id $id not found")
      case Some(cs) => IO(cs)
    }
  }

  def raiseErrorIO[A](msg: String): IO[A] = IO.raiseError[A](new Throwable(msg))
  def putLnIO(text: Any): IO[Unit]        = IO(println(text))

  def raiseErrorStream(msg: String)            = Stream.raiseError[IO](new Throwable(msg))
  def putLnStream(text: Any): Stream[IO, Unit] = Stream.eval(IO(println(text)))

  def noIvCT(ct: Array[Byte]) = CipherText[AES128CTR](RawCipherText(ct), Iv(Array.fill(16)(0: Byte)))
}

object Services {

  def getRouters() =
    IO(println("Obtaining list of routers")) *>
      IO(
        List(
          RouterToConnect("01", "127.0.0.1", 6666, 2, 2000000),
          RouterToConnect("02", "127.0.0.1", 6667, 3, 2000000),
          RouterToConnect("03", "127.0.0.1", 6668, 5, 2000000),
          RouterToConnect("04", "127.0.0.1", 6669, 6, 2000000),
          RouterToConnect("05", "127.0.0.1", 6670, 7, 2000000),
          RouterToConnect("06", "127.0.0.1", 6671, 10, 2000000)
        )
      )
}

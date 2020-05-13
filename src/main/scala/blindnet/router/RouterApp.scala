package blindnet.router

import cats.effect.IO._
import cats.effect._
import cats.effect.concurrent._
import com.comcast.ip4s._
import fs2.Stream
import fs2.io.tcp.SocketGroup
import fs2.io.tcp._
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._

object RouterApp1 extends IOApp {

  import Router._

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        val data = AppData("", 2, 10)
        val port = Port(6666).get
        val stream =
          Stream.eval_(IO(println(s"Starting server on port $port"))) ++
            Stream.eval(Ref[IO].of(Map.empty[String, RouterData])).flatMap { routers =>
              listenForConnections(socketGroup, routers, data, port)
                .concurrently(handleRoutersUpdating(routers))
            }

        stream.compile.drain
      }
    }.as(ExitCode.Success)
}
object RouterApp2 extends IOApp {

  import Router._

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        val data = AppData("", 2, 10)
        val port = Port(6667).get
        val stream =
          Stream.eval_(IO(println(s"Starting server on port $port"))) ++
            Stream.eval(Ref[IO].of(Map.empty[String, RouterData])).flatMap { routers =>
              listenForConnections(socketGroup, routers, data, port)
                .concurrently(handleRoutersUpdating(routers))
            }

        stream.compile.drain
      }
    }.as(ExitCode.Success)
}
object RouterApp3 extends IOApp {

  import Router._

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        val data = AppData("", 2, 10)
        val port = Port(6668).get
        val stream =
          Stream.eval_(IO(println(s"Starting server on port $port"))) ++
            Stream.eval(Ref[IO].of(Map.empty[String, RouterData])).flatMap { routers =>
              listenForConnections(socketGroup, routers, data, port)
                .concurrently(handleRoutersUpdating(routers))
            }

        stream.compile.drain
      }
    }.as(ExitCode.Success)
}
object RouterApp4 extends IOApp {

  import Router._

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        val data = AppData("", 2, 10)
        val port = Port(6669).get
        val stream =
          Stream.eval_(IO(println(s"Starting server on port $port"))) ++
            Stream.eval(Ref[IO].of(Map.empty[String, RouterData])).flatMap { routers =>
              listenForConnections(socketGroup, routers, data, port)
                .concurrently(handleRoutersUpdating(routers))
            }

        stream.compile.drain
      }
    }.as(ExitCode.Success)
}
object RouterApp5 extends IOApp {

  import Router._

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        val data = AppData("", 2, 10)
        val port = Port(6670).get
        val stream =
          Stream.eval_(IO(println(s"Starting server on port $port"))) ++
            Stream.eval(Ref[IO].of(Map.empty[String, RouterData])).flatMap { routers =>
              listenForConnections(socketGroup, routers, data, port)
                .concurrently(handleRoutersUpdating(routers))
            }

        stream.compile.drain
      }
    }.as(ExitCode.Success)
}
object RouterApp6 extends IOApp {

  import Router._

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        val data = AppData("", 2, 10)
        val port = Port(6671).get
        val stream =
          Stream.eval_(IO(println(s"Starting server on port $port"))) ++
            Stream.eval(Ref[IO].of(Map.empty[String, RouterData])).flatMap { routers =>
              listenForConnections(socketGroup, routers, data, port)
                .concurrently(handleRoutersUpdating(routers))
            }

        stream.compile.drain
      }
    }.as(ExitCode.Success)
}

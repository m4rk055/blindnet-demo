package blindnet.app

import scala.concurrent.duration._

import blindnet.client.Client._
import blindnet.client._
import cats.effect.IO._
import cats.effect._
import cats.implicits._
import cats.effect.concurrent._
import fs2.io.tcp._
import fs2.concurrent.Queue
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.blaze._
import org.http4s.client.dsl.io._
import org.http4s.client.blaze._
import org.http4s.implicits._
import org.http4s.circe._
import org.http4s.client.Client
import io.circe.generic.auto._
import io.circe.{ Decoder, Encoder }
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import java.time.LocalDateTime
import blindnet.monitor.Monitor.MonitorCellData
import blindnet.Endpoints

object DemoApp {

  case class SendData(to: String, msg: String)

  object SendData {
    implicit val enc: Encoder[SendData] = deriveEncoder[SendData]
    implicit val dec: Decoder[SendData] = deriveDecoder[SendData]
  }

  case class ReceiveMessage(from: String, msg: String)

  object ReceiveMessage {
    implicit val enc: Encoder[ReceiveMessage] = deriveEncoder[ReceiveMessage]
    implicit val dec: Decoder[ReceiveMessage] = deriveDecoder[ReceiveMessage]
  }

  // TODO: horrible
  def isStateOk(connections: Ref[IO, Connections]) =
    connections.get.map { cons =>
      val consList = cons.toList
      consList.length == 2 &&
      consList(0)._2.circuits.get.unsafeRunSync.toList(0)._2.isInstanceOf[ThreeHop] &&
      consList(1)._2.circuits.get.unsafeRunSync.toList(0)._2.isInstanceOf[ThreeHop]
    }.unsafeRunSync()

  def waitForOkState(connections: Ref[IO, Connections])(implicit t: Timer[IO]): IO[Unit] =
    if (isStateOk(connections)) IO.unit
    else IO.sleep(100 millis) *> waitForOkState(connections)

  implicit val sddec = jsonOf[IO, SendData]
  implicit val rmdec = jsonOf[IO, ReceiveMessage]

  def service(
    socketGroup: SocketGroup,
    connections: Ref[IO, Connections],
    completeMessages: Ref[IO, List[(String, String)]],
    q: Queue[IO, String],
    routerIds1: (Int, Int, Int),
    routerIds2: (Int, Int, Int),
    name: String,
    httpClient: Client[IO]
  )(implicit ctrStrategy: IvGen[IO, AES128CTR], cs: ContextShift[IO], t: Timer[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "create" =>
        for {
          ok <- if (isStateOk(connections))
                 Ok("circuits exits")
               else
                 connections.get.flatMap(cons => cons.toList.traverse(con => con._2.socket.close.attempt.void)) *>
                   createCircuits(socketGroup, connections, q, routerIds1, routerIds2, name, httpClient) *>
                   Ok("creating circuits")
        } yield ok

      case GET -> Root / "close" =>
        for {
          _  <- connections.get.flatMap(cons => cons.toList.traverse(con => con._2.socket.close.attempt.void))
          _  <- connections.set(Map.empty)
          ok <- Ok("circuits closed")
        } yield ok

      case req @ POST -> Root / "send" =>
        for {
          data <- req.as[SendData]
          ok <- if (isStateOk(connections))
                 sendMessage(
                   data.msg,
                   name,
                   data.to,
                   connections,
                   httpClient
                 ) *> Ok("messages sent")
               else
                 Ok("circuits not created")
        } yield ok

      case GET -> Root / "receive" =>
        for {
          messages <- completeMessages.getAndSet(Nil)
          ok       <- Ok(messages.map(msg => ReceiveMessage(msg._1, msg._2)).asJson)
        } yield ok

      case GET -> Root / "state" =>
        for {
          // cons   <- connections.get
          // cc     = cons.view.mapValues(c => c.circuits.get.unsafeRunSync()).toMap
          // output = cc.toList.map(c => s"${c._1} -> ${c._2.keySet.mkString(", ")}").mkString("\n")
          // ok     <- Ok(s"$output")
          ok <- if (isStateOk(connections)) Ok("ok") else Ok("not ok")
        } yield ok
    }

  def createCircuits(
    socketGroup: SocketGroup,
    connections: Ref[IO, Connections],
    q: Queue[IO, String],
    routerIds1: (Int, Int, Int),
    routerIds2: (Int, Int, Int),
    name: String,
    httpClient: Client[IO]
  )(implicit ctrStrategy: IvGen[IO, AES128CTR], cs: ContextShift[IO], t: Timer[IO]) =
    createCircuit(routerIds1, socketGroup, connections, q, name, httpClient).compile.drain.start *>
      IO.sleep(5 second) *>
      createCircuit(routerIds2, socketGroup, connections, q, name, httpClient).compile.drain.start

  def handleIncoming(
    httpClient: org.http4s.client.Client[IO],
    received: Ref[IO, Received],
    completeMessages: Ref[IO, List[(String, String)]],
    name: String
  )(implicit t: Timer[IO]): IO[Unit] = {
    val program =
      for {
        newCells <- httpClient.expect(s"${Endpoints.msgPool}/get/$name")(jsonOf[IO, List[Array[Byte]]])

        _ <- newCells.traverse(bytes =>
              for {
                now     <- IO(LocalDateTime.now())
                monCell = MonitorCellData(now, name, bytes, "in")
                req     = POST(monCell.asJson, Uri.unsafeFromString(s"${Endpoints.monitor}/push"))
                _ <- httpClient
                      .expect[String](req)
                      .handleErrorWith(e => IO(println(s"Error sending cell to monitor - $e")))
              } yield ()
            )

        _ <- if (newCells.length > 0) handleIncomingCells(received, completeMessages, newCells) else IO.unit

        _ <- IO.sleep(500 millis)
        _ <- handleIncoming(httpClient, received, completeMessages, name)
      } yield ()

    program.handleErrorWith(e => IO(println(e)))
  }
}

object MainAlice extends IOApp {

  import DemoApp._

  val name = "alice"

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global).resource.use { httpClient =>
          implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
            val zeros                      = Array.fill(16)(0: Byte)
            def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
            def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
          }

          for {
            _           <- IO(println(s"Starting app"))
            connections <- Ref.of[IO, Connections](Map.empty[RouterId, RouterConnection])
            q           <- Queue.bounded[IO, String](100)
            _           <- q.dequeue.evalMap(s => IO(println(s))).compile.drain.start
            // _           <- createCircuits(socketGroup, connections, q, (1, 2, 3), (4, 1, 5), name, httpClient)

            received         <- Ref.of[IO, Received](Map.empty)
            completeMessages <- Ref.of[IO, List[(String, String)]](List.empty)
            _                <- handleIncoming(httpClient, received, completeMessages, name).start

            _ <- BlazeServerBuilder[IO](scala.concurrent.ExecutionContext.global)
                  .bindHttp(8090, "0.0.0.0")
                  .withHttpApp(service(socketGroup, connections, completeMessages, q, (1, 2, 3), (4, 1, 5), name, httpClient).orNotFound)
                  .resource
                  .use(_ => IO.never)
          } yield ()
        }
      }
    }.as(ExitCode.Success)
}

object MainBob extends IOApp {

  import DemoApp._

  val name = "bob"

  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global).resource.use { httpClient =>
          implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
            val zeros                      = Array.fill(16)(0: Byte)
            def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
            def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
          }

          for {
            _           <- IO(println(s"Starting app"))
            connections <- Ref.of[IO, Connections](Map.empty[RouterId, RouterConnection])
            q           <- Queue.bounded[IO, String](100)
            _           <- q.dequeue.evalMap(s => IO(println(s))).compile.drain.start
            // _           <- createCircuits(socketGroup, connections, q, (6, 2, 4), (4, 5, 6), name, httpClient)

            received         <- Ref.of[IO, Received](Map.empty)
            completeMessages <- Ref.of[IO, List[(String, String)]](List.empty)
            _                <- handleIncoming(httpClient, received, completeMessages, name).start

            _ <- BlazeServerBuilder[IO](scala.concurrent.ExecutionContext.global)
                  .bindHttp(8091, "0.0.0.0")
                  .withHttpApp(service(socketGroup, connections, completeMessages, q, (6, 2, 4), (4, 5, 6), name, httpClient).orNotFound)
                  .resource
                  .use(_ => IO.never)
          } yield ()
        }
      }
    }.as(ExitCode.Success)
}

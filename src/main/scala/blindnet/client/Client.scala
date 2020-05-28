package blindnet.client

import java.time.LocalDateTime

import blindnet.model._
import cats.effect.IO._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.io.tcp._
import fs2.concurrent.Queue
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._
import tsec.common._
import tsec.hashing._
import tsec.hashing.jca._
import scala.util.Random
import blindnet.monitor.Monitor.MonitorCellData
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.client.{ Client => HttpClient }
import blindnet.monitor.Monitor.MonitorCellData._
import io.circe.syntax._
import blindnet.Endpoints
import org.http4s.Uri

// trait Client {
//   def createCircuit0(): Stream[IO, Unit]
// }

object Client {

  import Services._
  import Util._

  // def make(implicit cs: ContextShift[IO]) =
  //   Blocker[IO].use { blocker =>
  //     SocketGroup[IO](blocker).use { socketGroup =>
  //       // implicit val cachedInstance = AES128CTR.genEncryptor[IO]
  //       implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
  //         val zeros                      = Array.fill(16)(0: Byte)
  //         def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
  //         def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
  //       }

  //       for {
  //         connections <- Ref.of[IO, Connections](Map.empty[RouterId, RouterConnection])
  //       } yield new Client {

  //         def createCircuit0(): Stream[IO, Unit] = createCircuit((1, 2, 3), socketGroup, connections)
  //       }
  //     }
  //   }

  def sendToMonitor(name: String, bytes: Array[Byte], httpClient: HttpClient[IO], direction: String) =
    for {
      now     <- IO(LocalDateTime.now())
      monCell = MonitorCellData(now, name, bytes, direction)
      req     = POST(monCell.asJson, Uri.unsafeFromString(s"${Endpoints.monitor}/push"))
      _ <- httpClient
            .expect[String](req)
            .handleErrorWith(e => putLnIO(s"Error sending cell to monitor - $e"))
    } yield ()

  def createSocket(
    socketGroup: SocketGroup,
    router: RouterToConnect,
    connections: Ref[IO, Connections]
  )(implicit cs: ContextShift[IO]) = {
    val addr = SocketAddress(IpAddress(router.ip).get, Port(router.port).get)

    for {
      socket    <- Stream.resource(socketGroup.client[IO](addr.toInetSocketAddress))
      msgSocket <- Stream.eval(MessageSocketInstances.encryptedCells(socket))
      circState <- Stream.bracket(
                    for {
                      circState <- Ref.of[IO, Map[CircuitId, CircuitState]](Map.empty)
                      _         <- connections.update(_ + (router.id -> RouterConnection(msgSocket, circState)))
                    } yield circState
                  )(_ =>
                    putLnIO(s"Clearing socket connection ${router.id} ${router.ip}:${router.port}") *>
                      connections.update(_ - router.id) *>
                      socket.close.attempt.void
                  )
    } yield (circState, msgSocket)
  }

  def createCircuit(
    routerIds: (Int, Int, Int),
    socketGroup: SocketGroup,
    connections: Ref[IO, Connections],
    q: Queue[IO, String],
    name: String,
    httpClient: HttpClient[IO]
  )(
    implicit cs: ContextShift[IO],
    ctrStrategy: IvGen[IO, AES128CTR]
  ) =
    Stream.eval(getRouters()).flatMap { routers =>
      val (r1, r2, r3) = (routers(routerIds._1 - 1), routers(routerIds._2 - 1), routers(routerIds._3 - 1))

      def sendCreate(
        circStates: Ref[IO, Map[CircuitId, CircuitState]],
        msgSocket: MessageSocket[EncryptedCell, EncryptedCell]
      ) =
        for {
          circId <- circStates.get.map(_.keys.maxOption.fold(1: Short)(maxId => (maxId + 1).toShort))
          _      <- circStates.update(_ + (circId -> Established(circId, r1, r2, r3)))

          // TODO: DH
          x  <- IO(Random.between(1, 7))
          hs = (math.pow(r1.g, x) % r1.p).toInt
          _  <- putLnIO(s"Generating DH parameters for R1 x=$x hs=$hs")

          payload <- CREATE(hs).getBytes.toIO
          // TODO: encrypt with R1 SK
          ec = EncryptedCell(circId, 1, payload)
          _  <- msgSocket.write1(ec)
          _  <- sendToMonitor(name, ec.getBytesMonitoring, httpClient, "out")
          _  <- putLnIO(s"sending CREATE cell to R1 with hs=$hs, cId=${circId}")

          _ <- circStates.update(_.updated(circId, AwaitingCreated(circId, x, r1, r2, r3)))

        } yield ()

      val program =
        for {
          rConn <- Stream.eval(connections.get.map(_.get(r1.id)))
          _ <- rConn match {
                case Some(rConn) =>
                  putLnStream(s"Socket exist - ${r1.id} ${r1.ip}:${r1.port}") >>
                    Stream.eval(sendCreate(rConn.circuits, rConn.socket))
                case None =>
                  putLnStream(s"Creating socket to ${r1.id} ${r1.ip}:${r1.port}") >>
                    (for {
                      (circStates, msgSocket) <- createSocket(socketGroup, r1, connections)
                      _                       <- Stream.eval(sendCreate(circStates, msgSocket))
                      _                       <- process(msgSocket, circStates, q, name, httpClient)
                    } yield ())
              }
        } yield ()

      program.handleErrorWith {
        case e =>
          Stream.eval(IO(println(s"error with socket ${r1.id} ${r1.ip}:${r1.port} - ${e}"))) ++
            Stream.eval(
              connections.get.flatMap(cons => cons.toList.traverse(con => con._2.socket.close.attempt.void)) *>
                connections.set(Map.empty)
            )
      }
    }

  // TODO: IV, breaks the size of a cell (+16 bytes)
  def decryptCell(ec: EncryptedCell, circuitState: CircuitState): IO[Cell] = {

    def decryptOneHop(ec: EncryptedCell, k1: SecretKey[AES128CTR]): IO[Cell] =
      for {
        _ <- IO(println("decrypting with K1"))
        // payloadCT <- IO.fromEither(AES128CTR.ciphertextFromConcat(ec.payload))
        decryptedPayload <- AES128CTR.decrypt[IO](noIvCT(ec.payload), k1)
        cell             <- Cell.decodeCell(ec.circuitId, ec.command, decryptedPayload).toIO
      } yield cell

    def decryptTwoHop(ec: EncryptedCell, k1: SecretKey[AES128CTR], k2: SecretKey[AES128CTR]): IO[Cell] =
      for {
        _                 <- IO(println("decrypting with K1, K2"))
        decryptedPayload1 <- AES128CTR.decrypt[IO](noIvCT(ec.payload), k1)
        decryptedPayload2 <- AES128CTR.decrypt[IO](noIvCT(decryptedPayload1), k2)
        cell              <- Cell.decodeCell(ec.circuitId, ec.command, decryptedPayload2).toIO
      } yield cell

    def decryptThreeHop(
      ec: EncryptedCell,
      k1: SecretKey[AES128CTR],
      k2: SecretKey[AES128CTR],
      k3: SecretKey[AES128CTR]
    ): IO[Cell] =
      for {
        _                 <- IO(println("decrypting with K1, K2, K3"))
        decryptedPayload1 <- AES128CTR.decrypt[IO](noIvCT(ec.payload), k1)
        decryptedPayload2 <- AES128CTR.decrypt[IO](noIvCT(decryptedPayload1), k2)
        decryptedPayload3 <- AES128CTR.decrypt[IO](noIvCT(decryptedPayload2), k3)
        cell              <- Cell.decodeCell(ec.circuitId, ec.command, decryptedPayload3).toIO
      } yield cell

    circuitState match {
      case OneHop(_, _, Router(_, k1), _, _)                        => decryptOneHop(ec, k1)
      case TwoHop(_, _, Router(_, k1), Router(_, k2), _)            => decryptTwoHop(ec, k1, k2)
      case ThreeHop(_, Router(_, k1), Router(_, k2), Router(_, k3)) => decryptThreeHop(ec, k1, k2, k3)
      case cs =>
        raiseErrorIO(s"wrong circuit state $cs, expected circuit with established hops")
    }
  }

  def handleCreatedCell(
    messageSocket: MessageSocket[EncryptedCell, EncryptedCell],
    circuitStates: Ref[IO, Map[CircuitId, CircuitState]],
    cs: AwaitingCreated,
    hs: Int,
    kh: CryptoHash[SHA1],
    circId: Short,
    name: String,
    httpClient: HttpClient[IO]
  )(implicit ctrStrategy: IvGen[IO, AES128CTR]) =
    for {
      _ <- IO.unit

      key     = (math.pow(hs, cs.x) % cs.router1.p).toInt
      keyHash = key.toBytes.hash[SHA1]
      _       <- if (keyHash.sameElements(kh)) IO.unit else IO.raiseError(new Throwable("Bad key hash"))
      aesKey  <- AES128CTR.buildKey[IO]((1 to 12).map(_ => 0: Byte).toArray ++ key.toBytes)
      _       = println(s"session key for R1 = ${aesKey.key.getEncoded.toHexString}")

      x2  <- IO(Random.between(1, 7))
      hs2 = (math.pow(cs.router2.g, x2) % cs.router2.p).toInt
      _   <- putLnIO(s"DH for R2 x2=$x2 hs2=$hs2")

      _ <- circuitStates.update(_.updated(circId, OneHop(circId, x2, Router(cs.router1.id, aesKey), cs.router2, cs.router3)))

      relayCmd  = EXTEND(hs2, cs.router2.id)
      relayData <- relayCmd.getBytes.toIO
      digest    <- SHA1.hash[IO](relayData)

      // TODO: digest take 6 is not safe
      cell = Cell(RELAY(0, digest.take(6), 0, relayCmd), circId)

      cellPayload      <- IO.fromEither(cell.getPayload.leftMap(e => new Throwable(e)))
      encryptedPayload <- AES128CTR.encrypt[IO](PlainText(cellPayload), aesKey)

      encryptedCell = EncryptedCell(cell.circuitId, cell.cmd.getId, encryptedPayload.content)

      _ <- messageSocket.write1(encryptedCell)
      _ <- sendToMonitor(name, encryptedCell.getBytesMonitoring, httpClient, "out")
      _ <- putLnIO(s"sending EXTEND cell for R2 encrypted with K1, hs=$hs2")
    } yield ()

  def process(
    messageSocket: MessageSocket[EncryptedCell, EncryptedCell],
    circuitStates: Ref[IO, Map[CircuitId, CircuitState]],
    q: Queue[IO, String],
    name: String,
    httpClient: HttpClient[IO]
  )(implicit ctrStrategy: IvGen[IO, AES128CTR]): Stream[IO, Unit] =
    messageSocket.read
      .evalTap(ec => putLnIO(s"got cell cId=${ec.circuitId} command=${ec.command}"))
      .evalMap {

        case ec @ EncryptedCell(cId, 4, _) =>
          circuitStates.getState(cId).flatMap(circState => decryptCell(ec, circState))

        case EncryptedCell(cId, cmd, payload) =>
          Cell.decodeCell(cId, cmd, payload).toIO
      }
      .evalTap(cell => putLnIO((s"decoded: $cell")))
      .evalMap {

        case Cell(CREATED(hs, kh), cId) =>
          circuitStates.getState(cId).flatMap(cast[AwaitingCreated]).flatMap { circState =>
            handleCreatedCell(messageSocket, circuitStates, circState, hs, kh, cId, name, httpClient)
          }

        // case Destroy

        case Cell(RELAY(_, _, _, EXTENDED(hs, kh)), cId) =>
          for {
            circState <- circuitStates.getState(cId)
            _ <- circState match {
                  case OneHop(_, x2, r1, r2, r3) =>
                    for {
                      _ <- IO.unit

                      router2Key     = (math.pow(hs, x2) % r2.p).toInt
                      router2KeyHash = router2Key.toBytes.hash[SHA1]
                      _ <- if (router2KeyHash.sameElements(kh)) IO.unit
                          else IO.raiseError(new Throwable("Bad key hash"))

                      router2AesKey <- AES128CTR.buildKey[IO]((1 to 12).map(_ => 0: Byte).toArray ++ router2Key.toBytes)
                      _             = println(s"session key for R2 = ${router2AesKey.key.getEncoded.toHexString}")

                      x3  <- IO(Random.between(1, 7))
                      hs3 = (math.pow(r3.g, x3) % r3.p).toInt
                      _   <- putLnIO(s"DH for R3 x3=$x3 hs3=$hs3")

                      _ <- circuitStates.update(_.updated(cId, TwoHop(cId, x3, r1, Router(r2.id, router2AesKey), r3)))

                      relayCmd  = EXTEND(hs3, r3.id)
                      relayData <- relayCmd.getBytes.toIO
                      digest    <- SHA1.hash[IO](relayData)

                      // TODO: digest take 6 is not safe
                      payload           <- RELAY(0, digest.take(6), 0, relayCmd).getBytes.toIO
                      encryptedPayload1 <- AES128CTR.encrypt[IO](PlainText(payload), router2AesKey)
                      encryptedPayload2 <- AES128CTR.encrypt[IO](PlainText(encryptedPayload1.content), r1.key)

                      encryptedCell = EncryptedCell(cId, 4, encryptedPayload2.content)

                      _ <- putLnIO(s"sending EXTEND cell for R3 encrypted with K1 and K2, hs=$hs3")
                      _ <- messageSocket.write1(encryptedCell)
                      _ <- sendToMonitor(name, encryptedCell.getBytesMonitoring, httpClient, "out")
                    } yield ()

                  case TwoHop(_, x3, r1, r2, r3) =>
                    for {
                      _              <- IO.unit
                      router3Key     = (math.pow(hs, x3) % r3.p).toInt
                      router3KeyHash = router3Key.toBytes.hash[SHA1]

                      router3AesKey <- AES128CTR.buildKey[IO]((1 to 12).map(_ => 0: Byte).toArray ++ router3Key.toBytes)
                      _             = println(s"session key for R3 = ${router3AesKey.key.getEncoded.toHexString}")

                      _ <- if (router3KeyHash.sameElements(kh)) IO.unit else IO.raiseError(new Throwable("Bad key hash"))

                      _ <- circuitStates.update(_.updated(cId, ThreeHop(cId, r1, r2, Router(r3.id, router3AesKey))))

                      _ <- putLnIO("successfully created circuit")
                      _ <- putLnIO("----------------------------")

                      _ <- q.enqueue1(s"yeeeeej ${r1.id} -> $cId")

                    } yield ()

                  case s => IO.raiseError(new Throwable(s"invalid state $s for EXTENDED command"))
                }
          } yield ()

        case cell => IO.raiseError(new Throwable(s"not implemented handling for cell $cell"))
      }

  def sendMessage(
    msg: String,
    from: String,
    to: String,
    connections: Ref[IO, Connections],
    httpClient: HttpClient[IO]
  )(implicit ctrStrategy: IvGen[IO, AES128CTR]) =
    for {
      _              <- IO.unit
      twoConnections <- connections.get.map(cons => cons.take(2).map(_._2))

      circuits <- twoConnections.toList.traverse(connection =>
                   connection.circuits.get.map(_.head).flatMap(c => cast[ThreeHop](c._2)).map((connection.socket, _))
                 )

      msgId = Random.nextInt(255).toByte

      // 1 byte for toLen, toLen Bytes for to, 1 byte for msgId,
      // 1 byte for i, 1 byte for end, 1 by for forLen, forLen bytes for from
      // 2 bytes for msgLen
      cellDataLen  = 498 - 1 - to.length() - 1 - 1 - 1 - 1 - from.length() - 2
      bytes        = msg.utf8Bytes
      groupedBytes = bytes.grouped(cellDataLen).toList

      key <- AES128CTR.buildKey[IO](Array[Byte](1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3))

      cells <- groupedBytes.zipWithIndex.traverse {
                case (gb, i) =>
                  val isLast: Byte = if (i == groupedBytes.length - 1) 1 else 0
                  val full =
                    Array[Byte](i.toByte) ++
                      Array[Byte](isLast) ++
                      Array[Byte](msgId) ++
                      Array(from.length().toByte) ++
                      from.getBytes ++
                      java.nio.ByteBuffer.allocate(2).putShort(gb.length.toShort).array() ++
                      gb ++
                      Array.fill[Byte](cellDataLen - gb.length)(0)

                  AES128CTR
                    .encrypt[IO](PlainText(full), key)
                    .map(eFull =>
                      Cell(
                        RELAY(
                          0,
                          (Array(to.length.toByte) ++ to.getBytes() ++ eFull.content).hash[SHA1].take(6),
                          gb.length.toShort,
                          DATA(to, to.length.toByte, eFull.content)
                        ),
                        circuits(i % 2)._2.circuitId
                      )
                    )
              }

      _ <- cells.zipWithIndex.traverse {
            case (cell, i) =>
              for {
                payload           <- cell.getPayload.toIO
                encryptedPayload1 <- AES128CTR.encrypt[IO](PlainText(payload), circuits(i % 2)._2.router3.key)
                encryptedPayload2 <- AES128CTR.encrypt[IO](PlainText(encryptedPayload1.content), circuits(i % 2)._2.router2.key)
                encryptedPayload3 <- AES128CTR.encrypt[IO](PlainText(encryptedPayload2.content), circuits(i % 2)._2.router1.key)

                ec = EncryptedCell(cell.circuitId, cell.cmd.getId, encryptedPayload3.content)

                _ <- circuits(i % 2)._1.write1(ec)

                _ <- sendToMonitor(from, ec.getBytesMonitoring, httpClient, "out")

              } yield ()
          }

      _ <- putLnIO(s"${cells.length} cells sent")

    } yield ()

  case class IncomingCell(
    i: Byte,
    last: Boolean,
    msgId: Byte,
    from: String,
    content: String
  )

  // from -> (msgId -> (i, last, content))
  type Received = Map[String, Map[Byte, List[(Byte, Boolean, String)]]]

  def handleIncomingCells(
    received: Ref[IO, Received],
    completeMessages: Ref[IO, List[(String, String)]],
    newCells: List[Array[Byte]]
  ) =
    for {
      key <- AES128CTR.buildKey[IO](Array[Byte](1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3))

      decrypted <- newCells.traverse { cell =>
                    AES128CTR.decrypt[IO](noIvCT(cell), key).map { data =>
                      val i       = data.take(1).head
                      val last    = data.drop(1).take(1).head == 1.toByte
                      val msgId   = data.drop(2).take(1).head
                      val fromLen = data.drop(3).take(1).head
                      val from    = data.drop(4).take(fromLen).toAsciiString
                      val len     = data.drop(4 + fromLen).take(2).toShortUnsafe
                      val content = data.drop(4 + fromLen + 2).take(len).toAsciiString

                      IncomingCell(i, last, msgId, from, content)
                    }
                  }

      _ <- decrypted.traverse(cell =>
            received.update(rec =>
              rec.get(cell.from) match {
                case Some(from) =>
                  from.get(cell.msgId) match {
                    case Some(cells) =>
                      rec.updated(cell.from, from.updated(cell.msgId, cells :+ (cell.i, cell.last, cell.content)))
                    case None =>
                      rec.updated(cell.from, from + (cell.msgId -> List((cell.i, cell.last, cell.content))))
                  }
                case None =>
                  rec + (cell.from -> Map(cell.msgId -> List((cell.i, cell.last, cell.content))))
              }
            )
          )

      rec <- received.get
      _ <- rec.toList.traverse {
            case (from, cells) =>
              cells.toList.traverse {
                case (msgId, msgs) => {
                  val all  = msgs.sortBy(_._1)
                  val last = all.last
                  if (last._2 == true && last._1 == all.size - 1) {
                    completeMessages.update(compl => compl :+ (from, all.map(_._3).mkString)) *>
                      received.update(rec => rec.updated(from, rec(from) - msgId))
                  } else IO.unit
                }
              }
          }

    } yield ()
}

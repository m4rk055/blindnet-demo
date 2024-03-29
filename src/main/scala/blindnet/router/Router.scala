package blindnet.router

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime

import scala.concurrent.duration.FiniteDuration

import blindnet.model._
import cats.effect.IO._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.io.tcp.SocketGroup
import fs2.io.tcp._
import tsec.cipher.common.padding._
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._
import tsec.cipher.symmetric.jca.primitive._
import tsec.common._
import tsec.hashing.jca._
import org.http4s.client.blaze._
import org.http4s.client._
import org.http4s.client.dsl.io._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.dsl.io._
import org.http4s.circe._
import blindnet.monitor.Monitor.MonitorCellData._
import blindnet.monitor.Monitor.MonitorCellData
import blindnet.Endpoints
import org.http4s.Uri

object Router {

  import Util._

  def sendToMonitor(name: String, bytes: Array[Byte], httpClient: Client[IO], direction: String) =
    for {
      now     <- IO(LocalDateTime.now())
      monCell = MonitorCellData(now, name, bytes, direction)
      req     = POST(monCell.asJson, Uri.unsafeFromString(s"${Endpoints.monitor}/push"))
      _ <- httpClient
            .expect[String](req)
            .handleErrorWith(e => putLnIO(s"Error sending cell to monitor - $e"))
    } yield ()

  def clearConnections(routerConnections: Ref[IO, Connections]) =
    routerConnections.get.flatMap(_.toList.traverse(_._2.socket.close.attempt.void)) *>
      routerConnections.set(Map.empty)

  def clearCircuits(circuits: Ref[IO, Map[Short, RCircuitState]]) =
    circuits.get.flatMap(circuit =>
      circuit.toList.traverse(_._2 match {
        case AwaitingNextHop(_, next) => next.messageSocket.close.attempt.void
        case Complete(_, next)        => next.messageSocket.close.attempt.void
        case _                        => IO.unit
      })
    ) *>
      circuits.set(Map.empty)

  def clearAll(circuits: Ref[IO, Map[Short, RCircuitState]], routerConnections: Ref[IO, Connections]) =
    clearCircuits(circuits) *>
      clearConnections(routerConnections)

  def listenForConnections(
    socketGroup: SocketGroup,
    routers: Ref[IO, Map[String, RouterData]],
    appData: AppData,
    port: Port
  )(
    implicit cs: ContextShift[IO],
    ctrStrategy: IvGen[IO, AES128CTR],
    cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ) =
    Stream.eval(Ref.of[IO, Connections](Map.empty[RouterId, RouterConnection])).flatMap { routerConnections =>
      Stream.resource(BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global).resource).flatMap { httpClient =>
        socketGroup
          .server[IO](new InetSocketAddress(port.value))
          .map { clientSocketResource =>
            Stream
              .resource(clientSocketResource)
              .flatMap(clientSocket =>
                putLnStream(s"NEW CONNECTION") >>
                  (for {
                    circuits      <- Stream.eval(Ref[IO].of(Map.empty[Short, RCircuitState]))
                    messageSocket <- Stream.eval(MessageSocketInstances.encryptedCells(clientSocket))
                    _ <- handleConnection(
                          socketGroup,
                          messageSocket,
                          // clientSocket,
                          circuits,
                          routers,
                          appData,
                          routerConnections,
                          httpClient
                        ).onFinalize(clearCircuits(circuits) *> clientSocket.close.attempt.void)
                  } yield ())
              )
              .onFinalize(putLnIO("connection disconnected") *> clearConnections(routerConnections))
          }
          .parJoinUnbounded
      }
    }

  def handleNextHopConnection(
    nextMsgSocket: MessageSocket[EncryptedCell, EncryptedCell],
    next: RouterData,
    rConn: RouterConnection,
    circuits: Ref[IO, Map[Short, RCircuitState]],
    previousMessageSocket: MessageSocket[EncryptedCell, EncryptedCell],
    httpClient: Client[IO],
    appData: AppData
  )(
    implicit cs: ContextShift[IO],
    ctrStrategy: IvGen[IO, AES128CTR],
    cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ) =
    nextMsgSocket.read
      .evalTap(ec => putLnIO(s"BACKWARD got encrypted cell from router ${next.id} cId=${ec.circuitId} command=${ec.command}"))
      .evalTap(ec => sendToMonitor(appData.routerId, ec.getBytesMonitoring, httpClient, "in"))
      .evalTap {

        case EncryptedCell(cId, 2, payload) =>
          for {
            previousCircId <- rConn.mappings.get.map(_.get(cId))
            _ <- previousCircId match {
                  case None => putLnIO(s"Previous circuit id for $cId not found")
                  case Some(prevCid) =>
                    for {
                      cell <- Cell.decodeCell(cId, 2, payload).toIO

                      _ <- cell.cmd match {
                            case CREATED(hs, kh) =>
                              for {
                                _    <- IO.unit
                                cell = Cell(RELAY(cmd = EXTENDED(hs, kh)), prevCid)

                                cs <- circuits.get.flatMap(
                                       _.get(cId)
                                         .fold(
                                           raiseErrorIO[RCircuitState](s"circuit $cId not found")
                                         )(cs => IO(cs))
                                     )

                                cellPayload      <- cell.getPayload.toIO
                                encryptedPayload <- AES128CTR.encrypt[IO](PlainText(cellPayload), cs.key)

                                encryptedCell = EncryptedCell(
                                  cell.circuitId,
                                  cell.cmd.getId,
                                  encryptedPayload.content
                                )

                                _ <- putLnIO(s"BACKWARD sending RELAY EXTENDED cell")
                                _ <- previousMessageSocket.write1(encryptedCell)
                                _ <- sendToMonitor(appData.routerId, encryptedCell.getBytesMonitoring, httpClient, "out")
                                // TODO: unsafe
                                _ <- circuits.update(circs =>
                                      circs.updatedWith(prevCid)(x => Complete(x.get.key, x.get.asInstanceOf[AwaitingNextHop].next).some)
                                    )

                              } yield ()
                          }
                    } yield ()
                }
          } yield ()

        case EncryptedCell(cId, 4, payload) =>
          for {
            previousCircId <- rConn.mappings.get.map(_.get(cId))
            _ <- previousCircId match {
                  case None => putLnIO(s"Previous circuit id for $cId not found")
                  case Some(prevCid) =>
                    for {
                      cs <- circuits.get.flatMap(
                             _.get(cId)
                               .fold(
                                 raiseErrorIO[RCircuitState](s"circuit $cId not found")
                               )(cs => IO(cs))
                           )

                      encryptedPayload <- AES128CTR.encrypt[IO](PlainText(payload), cs.key)

                      encryptedCell = EncryptedCell(
                        prevCid,
                        4,
                        encryptedPayload.content
                      )

                      _ <- putLnIO(s"BACKWARD sending RELAY cell cId=$prevCid, cmd=4")
                      _ <- previousMessageSocket.write1(encryptedCell)
                      _ <- sendToMonitor(appData.routerId, encryptedCell.getBytesMonitoring, httpClient, "out")

                    } yield ()

                }
          } yield ()

        case cell => IO.raiseError(new Throwable(s"not implemented handling for cell $cell"))
      }

  def handleRelayCell(
    socketGroup: SocketGroup,
    previousMessageSocket: MessageSocket[EncryptedCell, EncryptedCell],
    previousCircuitId: Short,
    relayCommand: RELAY,
    routers: Ref[IO, Map[String, RouterData]],
    circuits: Ref[IO, Map[Short, RCircuitState]],
    routerConnections: Ref[IO, Connections],
    httpClient: Client[IO],
    appData: AppData
  )(
    implicit cs: ContextShift[IO],
    ctrStrategy: IvGen[IO, AES128CTR],
    cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ) =
    relayCommand match {
      case RELAY(_, _, _, EXTEND(hs, rId)) =>
        Stream
          .eval(for {
            cs <- circuits.get.flatMap(
                   _.get(previousCircuitId)
                     .fold(raiseErrorIO[RCircuitState](s"circuit $previousCircuitId not found"))(cs => IO(cs))
                 )
            _ <- cast[KeyExchanged](cs)

            nextRouterData <- routers.get.map(_.get(rId)).flatMap {
                               case None     => raiseErrorIO(s"router not found id=$rId")
                               case Some(rd) => IO(rd)
                             }
            _ <- putLnIO(s"FORWARD next hop: $nextRouterData")

          } yield (cs, nextRouterData))
          .flatMap {
            case (circState, next) =>
              for {
                rConn <- Stream.eval(routerConnections.get.map(_.get(next.id)))
                _ <- rConn match {
                      case Some(rConn) =>
                        for {
                          _ <- putLnStream(s"Socket exist - ${next.id} ${next.ip}:${next.port}")
                          nextCircId <- Stream.eval(rConn.circuits.modify { cIds =>
                                         val next = (cIds.max + 1).toShort
                                         println(cIds, cIds.max, cIds.max + 1)
                                         (cIds + next, next)
                                       })
                          _ <- putLnStream(s"Circuit id = $nextCircId")

                          cell    = Cell(CREATE(hs), nextCircId)
                          payload <- Stream.eval(IO.fromEither(cell.getPayload))
                          _       <- putLnStream(s"FORWARD sending CREATE cell to router ${next.id}")
                          ec      = EncryptedCell(cell.circuitId, cell.cmd.getId, payload)
                          _       <- Stream.eval(rConn.socket.write1(ec))
                          _       <- Stream.eval(sendToMonitor(appData.routerId, ec.getBytesMonitoring, httpClient, "out"))

                          nextHop = NextHop(rConn.socket, nextCircId)
                          _       <- Stream.eval(circuits.update(_ + (previousCircuitId -> AwaitingNextHop(circState.key, nextHop))))
                          _       <- Stream.eval(rConn.mappings.update(_ + (nextCircId -> previousCircuitId)))
                        } yield ()

                      case None => {

                        val nextAddr = SocketAddress(IpAddress(next.ip).get, Port(next.port).get)

                        val program = for {
                          _             <- putLnStream(s"Creating socket to ${next.id} ${next.ip}:${next.port}")
                          socket        <- Stream.resource(socketGroup.client[IO](nextAddr.toInetSocketAddress))
                          nextMsgSocket <- Stream.eval(MessageSocketInstances.encryptedCells(socket))

                          nextCircId = (1: Short)

                          rConn <- Stream.bracket(
                                    for {
                                      circuits <- Ref.of[IO, Set[CircuitId]](Set(nextCircId))
                                      mappings <- Ref.of[IO, Map[CircuitId, CircuitId]](Map())
                                      rConn    = RouterConnection(nextMsgSocket, circuits, mappings)
                                      _        <- routerConnections.update(_ + (next.id -> rConn))
                                    } yield rConn
                                  )(_ =>
                                    putLnIO(s"Clearing socket connection ${next.id} ${next.ip}:${next.port}") *>
                                      routerConnections.update(_ - next.id) *>
                                      socket.close.attempt.void *>
                                      previousMessageSocket.close.attempt.void
                                  )

                          cell    = Cell(CREATE(hs), nextCircId)
                          payload <- Stream.eval(IO.fromEither(cell.getPayload))
                          _       <- putLnStream(s"FORWARD sending CREATE cell to router ${next.id}")
                          ec      = EncryptedCell(cell.circuitId, cell.cmd.getId, payload)
                          _       <- Stream.eval(nextMsgSocket.write1(ec))
                          _       <- Stream.eval(sendToMonitor(appData.routerId, ec.getBytesMonitoring, httpClient, "out"))

                          nextHop = NextHop(nextMsgSocket, nextCircId)
                          _       <- Stream.eval(circuits.update(_ + (previousCircuitId -> AwaitingNextHop(circState.key, nextHop))))
                          _       <- Stream.eval(rConn.mappings.update(_ + (nextCircId -> previousCircuitId)))

                          _ <- handleNextHopConnection(nextMsgSocket, next, rConn, circuits, previousMessageSocket, httpClient, appData)
                        } yield ()

                        Stream.eval(
                          program
                            .handleErrorWith(e =>
                              putLnStream(s"error ee - $e") *>
                                Stream.eval(
                                  clearAll(circuits, routerConnections) *>
                                    previousMessageSocket.close.attempt.void
                                )
                            )
                            .compile
                            .drain
                            .start
                        )
                      }
                    }
              } yield ()
          }

      case RELAY(_, _, _, DATA(to, _, msg)) =>
        Stream
          .eval(for {
            _ <- putLnIO("forwarding cell to msg pool")

            cell = blindnet.msgpool.CellData(to, msg)
            req  = POST(cell.asJson, Uri.unsafeFromString(s"${Endpoints.msgPool}/enqueue"))
            _ <- httpClient
                  .expect[String](req)
                  .handleErrorWith(e => putLnIO(s"Sending cell to message pool failed - $e"))

            _ <- sendToMonitor(appData.routerId, cell.data, httpClient, "out")

          } yield ())
          .handleErrorWith(_ => Stream.emit(()))

      case r =>
        putLnStream(s"unexpected relay cell $r")
    }

  def handleCreateCell(
    messageSocket: MessageSocket[EncryptedCell, EncryptedCell],
    circuits: Ref[IO, Map[Short, RCircuitState]],
    handshake: Int,
    appData: AppData,
    cId: Short,
    httpClient: Client[IO]
  ) =
    for {
      _ <- IO.unit
      // y      <- IO(Random.between(1, 10))
      y      = 1
      key    = (math.pow(handshake, y) % appData.p).toInt
      aesKey <- AES128CTR.buildKey[IO]((1 to 12).map(_ => 0: Byte).toArray ++ key.toBytes)
      _      <- putLnIO(s"session key=${aesKey.key.getEncoded.toHexString}")
      _      <- circuits.update(_ + (cId -> KeyExchanged(aesKey)))

      hs      = (math.pow(appData.g, y) % appData.p).toInt
      keyHash = key.toBytes.hash[SHA1]
      _       <- putLnIO(s"DH y=$y hs=$hs key_hash=${keyHash.toHexString}")

      cell = Cell(CREATED(hs, keyHash), cId)

      cellPayload <- cell.getPayload.toIO

      encryptedCell = EncryptedCell(cell.circuitId, cell.cmd.getId, cellPayload)

      _ <- putLnIO(s"BACKWARD sending CREATED cell")
      _ <- messageSocket.write1(encryptedCell)
      _ <- sendToMonitor(appData.routerId, encryptedCell.getBytesMonitoring, httpClient, "out")
    } yield ()

  def handleConnection(
    socketGroup: SocketGroup,
    messageSocket: MessageSocket[EncryptedCell, EncryptedCell],
    // socket: Socket[IO],
    circuits: Ref[IO, Map[Short, RCircuitState]],
    routers: Ref[IO, Map[String, RouterData]],
    appData: AppData,
    routerConnections: Ref[IO, Connections],
    httpClient: Client[IO]
  )(
    implicit cs: ContextShift[IO],
    ctrStrategy: IvGen[IO, AES128CTR],
    cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ) =
    messageSocket.read
      .evalTap(ec => putLnIO(s"FORWARD got cell cId=${ec.circuitId} command=${ec.command}"))
      .evalTap(ec => sendToMonitor(appData.routerId, ec.getBytesMonitoring, httpClient, "in"))
      .flatMap {
        case EncryptedCell(cId, 1, payload) =>
          Stream.eval(
            for {
              cell <- Cell.decodeCell(cId, 1, payload).toIO
              _ <- cell.cmd match {
                    case CREATE(hs) =>
                      putLnIO(s"FORWARD decoded CREATE cell hs=$hs, cId=${cell.circuitId}")
                      handleCreateCell(messageSocket, circuits, hs, appData, cell.circuitId, httpClient)
                  }
            } yield ()
          )

        case EncryptedCell(_, 3, _) =>
          raiseErrorStream("FORWARD destroy cell not handled")

        case EncryptedCell(cId, 4, payload) =>
          for {
            mCircState <- Stream.eval(circuits.get.map(_.get(cId)))
            circState  <- Stream.eval(mCircState.fold(raiseErrorIO[RCircuitState](s"FORWARD circuit $cId not found"))(cs => IO(cs)))

            _                <- putLnStream(s"FORWARD decrypting payload for RELAY cell, cId = ${cId}")
            payloadCT        = noIvCT(payload)
            decryptedPayload <- Stream.eval(AES128CTR.decrypt[IO](payloadCT, circState.key))

            cell <- Stream.eval(Cell.decodeCell(cId, 4, decryptedPayload).toIO.attempt)
            _ <- cell.fold(
                  _ =>
                    Stream.eval(
                      circState match {
                        case complete: Complete =>
                          for {
                            _       <- IO.unit
                            newCell = EncryptedCell(complete.next.cirId, 4, decryptedPayload)
                            _       <- putLnIO(s"FORWARD cell not decoded, sending to next router, cId = ${newCell.circuitId}")
                            _       <- complete.next.messageSocket.write1(newCell)
                            _       <- sendToMonitor(appData.routerId, newCell.getBytesMonitoring, httpClient, "out")
                          } yield ()
                        case _ =>
                          putLnIO("Cell not properly decoded, no router to forward to") *>
                            clearAll(circuits, routerConnections) *>
                            messageSocket.close.attempt.void
                      }
                    ),
                  cell =>
                    cell.cmd match {
                      case r: RELAY =>
                        for {
                          relayData <- Stream.eval(r.cmd.getBytes.toIO)
                          digest    = relayData.hash[SHA1]

                          _ <- if (digest.take(6).sameElements(r.digest))
                                putLnStream("FORWARD good digest, handling") ++
                                  handleRelayCell(
                                    socketGroup,
                                    messageSocket,
                                    cId,
                                    r,
                                    routers,
                                    circuits,
                                    routerConnections,
                                    httpClient,
                                    appData
                                  )
                              else
                                Stream.eval(
                                  circState match {
                                    case complete: Complete =>
                                      for {
                                        _       <- IO.unit
                                        newCell = EncryptedCell(complete.next.cirId, 4, decryptedPayload)
                                        _       <- putLnIO("FORWARD bad digest, sending to next router")
                                        _       <- complete.next.messageSocket.write1(newCell)
                                        _       <- sendToMonitor(appData.routerId, newCell.getBytesMonitoring, httpClient, "out")
                                      } yield ()
                                    case _ =>
                                      putLnIO("Cell bad digest, no router to forward to") *>
                                        clearAll(circuits, routerConnections) *>
                                        messageSocket.close.attempt.void
                                  }
                                )
                        } yield ()
                    }
                )
          } yield ()

        case ec => putLnStream(s"FORWARD unexpected command ${ec.command}")
      }
      .handleErrorWith {
        case e =>
          Stream.eval_(
            putLnIO(s"closed connection - $e") *>
              clearAll(circuits, routerConnections) *>
              messageSocket.close.attempt.void
          )
      }

  def updateRoutersData(routers: Ref[IO, Map[String, RouterData]]) =
    putLnIO("retrieving routers") *>
      routers.update(_ => Endpoints.routers.map(r => r._1 -> RouterData(r._1, r._2, r._3)).toMap)

  def handleRoutersUpdating(routers: Ref[IO, Map[String, RouterData]])(implicit t: Timer[IO]) =
    Stream
      .repeatEval(updateRoutersData(routers))
      .interleave(
        Stream.awakeDelay[IO](
          FiniteDuration(3600, TimeUnit.SECONDS)
        )
      ),
}

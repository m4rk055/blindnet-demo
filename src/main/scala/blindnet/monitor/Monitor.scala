package blindnet.monitor

import java.time.LocalDateTime

import cats.effect.IO._
import cats.effect._
import cats.effect.concurrent._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.blaze._
import org.http4s.implicits._
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.{ Decoder, Encoder }
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

object Monitor extends IOApp {

  case class MonitorCellData(
    timestamp: LocalDateTime,
    transferPointId: String,
    cellData: Array[Byte],
    direction: String
  )

  object MonitorCellData {
    implicit val enc: Encoder[MonitorCellData] = deriveEncoder[MonitorCellData]
    implicit val dec: Decoder[MonitorCellData] = deriveDecoder[MonitorCellData]
  }

  implicit val dec = jsonOf[IO, MonitorCellData]

  def service(monitorCells: Ref[IO, List[MonitorCellData]]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ POST -> Root / "push" =>
        for {
          cell <- req.as[MonitorCellData]
          _    <- IO(println(s"Got cell from ${cell.transferPointId} ${cell.direction.toUpperCase()}"))
          _    <- monitorCells.update(cells => cells :+ cell)
          ok   <- Ok("")
        } yield ok

      case GET -> Root / "read" =>
        for {
          cells <- monitorCells.getAndSet(List.empty)
          ok    <- Ok(cells.asJson)
        } yield ok
    }

  def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO(println(s"Starting monitor"))

      monitorCells <- Ref.of[IO, List[MonitorCellData]](List.empty)

      _ <- BlazeServerBuilder[IO](scala.concurrent.ExecutionContext.Implicits.global)
            .bindHttp(8082, "0.0.0.0")
            .withHttpApp(service(monitorCells).orNotFound)
            .resource
            .use(_ => IO.never)

    } yield ExitCode.Success

}

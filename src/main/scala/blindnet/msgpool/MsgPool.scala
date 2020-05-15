package blindnet.msgpool

import cats.effect.IO._
import cats.effect._
import cats.effect.concurrent._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze._
import io.circe.generic.auto._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.blaze._
import org.http4s.implicits._
import org.http4s.circe._
import io.circe.syntax._

object MsgPool extends IOApp {

  type EndpointId = String
  type State      = Map[EndpointId, List[Array[Byte]]]

  implicit val dec = jsonOf[IO, CellData]

  def service(state: Ref[IO, State]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ POST -> Root / "enqueue" =>
        for {
          cell <- req.as[CellData]
          _    <- IO(println(s"received cell for ${cell.to}"))
          _ <- state.update { st =>
                val msgs = st.get(cell.to).getOrElse(Nil)
                st.updated(cell.to, msgs :+ cell.data)
              }
          ok <- Ok()
        } yield ok

      case GET -> Root / "get" / id =>
        for {
          msgs <- state.get.map(_.get(id)).map(_.getOrElse(Nil))
          _    <- state.update(_.updated(id, Nil))
          ok   <- Ok(msgs.asJson)
        } yield ok
    }

  def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO(println(s"Starting message pool"))

      state <- Ref.of[IO, State](Map.empty)

      _ <- BlazeServerBuilder[IO](scala.concurrent.ExecutionContext.Implicits.global)
            .bindHttp(8081, "localhost")
            .withHttpApp(service(state).orNotFound)
            .resource
            .use(_ => IO.never)

    } yield ExitCode.Success

}

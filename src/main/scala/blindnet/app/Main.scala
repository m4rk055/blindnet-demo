package blindnet.app

import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze._

import cats.effect.concurrent._
import cats.effect.IO._
import fs2.io.tcp._
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._

import blindnet.client._
import blindnet.client.Client._

import cats.effect._
import cats.effect.concurrent._
import cats.effect.IO._
import fs2.io.tcp._

import tsec.cipher.symmetric._
import tsec.cipher.symmetric.jca._
// import tsec.cipher.symmetric.jca.primitive._
// import tsec.cipher.common.padding._

import scala.concurrent.duration._

// object Main extends IOApp {

//   def appService(
//     client: Client
//   ): HttpRoutes[IO] =
//     HttpRoutes.of[IO] {
//       case GET -> Root / "create" =>
//         for {
//           _  <- client.createCircuit0().compile.drain.start
//           ok <- Ok()
//         } yield ok
//     }

//   def httpApp(client: Client): HttpApp[IO] =
//     Router("/" -> appService(client)).orNotFound

//   // with stream
//   def run(args: List[String]): IO[ExitCode] =
//     for {
//       _      <- IO(println(s"Starting app"))
//       client <- Client.make
//       _ <- BlazeServerBuilder[IO]
//             .bindHttp(8080, "localhost")
//             .withHttpApp(httpApp(client))
//             .resource
//             .use(_ => IO.never)

//     } yield ExitCode.Success
// }

object Main extends IOApp {

//  implicit val cs: ContextShift[IO] = IO.contextShift(global)
//  implicit val timer: Timer[IO]     = IO.timer(global)

  def appService(
    socketGroup: SocketGroup,
    connections: Ref[IO, Connections]
  )(
    implicit ctrStrategy: IvGen[IO, AES128CTR]
    // cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "create" =>
        for {
          _ <- createCircuit((1, 2, 3), socketGroup, connections).compile.drain.start
          // (rId, circId) <- promise.take
          // ok <- Ok(s"Created router = $rId, circuit = $circId")
          ok <- Ok()
        } yield ok

      case GET -> Root / "send" =>
        for {
          // cons <- connections.get
          _ <- sendMessage(
                "ovo je jedan poduzi tekst koji ce se razdvojiti u vise celija koje putuju kroz blindnet. Shakespeare was born and raised in Stratford-upon-Avon, Warwickshire. At the age of 18, he married Anne Hathaway, with whom he had three children: Susanna and twins Hamnet and Judith. Sometime between 1585 and 1592, he began a successful career in London as an actor, writer, and part-owner of a playing company called the Lord Chamberlain's Men, later known as the King's Men. At age 49 (around 1613), he appears to have retired to Stratford, where he died three years later. Few records of Shakespeare's private life survive; this has stimulated considerable speculation about such matters as his physical appearance, his sexuality, his religious beliefs, and whether the works attributed to him were written by others. Shakespeare was born, brought up, and buried in Stratford-upon-Avon, where he maintained a household throughout the duration of his career in London. A market town of around 1,500 residents about 100 miles (160 km) north-west of London, Stratford was a centre for the slaughter, marketing, and distribution of sheep, as well as for hide tanning and wool trading. Anti-Stratfordians often portray the town as a cultural backwater lacking the environment necessary to nurture a genius, and depict Shakespeare as ignorant and illiterate. Anti-Stratfordians consider Shakespeare's background incompatible with that attributable to the author of the Shakespeare canon, which exhibits an intimacy with court politics and culture, foreign countries, and aristocratic sports such as hunting, falconry, tennis, and lawn-bowling.",
                connections
              )

          // (rId, circId) <- promise.take
          // ok <- Ok(s"Created router = $rId, circuit = $circId")
          ok <- Ok()
        } yield ok

      case GET -> Root / "state" =>
        for {
          cons   <- connections.get
          cc     = cons.view.mapValues(c => c.circuits.get.unsafeRunSync()).toMap
          output = cc.toList.map(c => s"${c._1} -> ${c._2.keySet.mkString(", ")}").mkString("\n")
          ok     <- Ok(s"$output")
        } yield ok
    }

  def httpApp(
    socketGroup: SocketGroup,
    connections: Ref[IO, Connections]
  )(
    implicit ctrStrategy: IvGen[IO, AES128CTR]
    // cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ): HttpApp[IO] =
    Router(
      "/" -> appService(socketGroup, connections)
    ).orNotFound

  def createCircuits(
    socketGroup: SocketGroup,
    connections: Ref[IO, Connections]
  )(
    implicit ctrStrategy: IvGen[IO, AES128CTR]
    // cachedInstance: JCAPrimitiveCipher[IO, AES128CTR, CTR, NoPadding]
  ) =
    createCircuit((1, 2, 3), socketGroup, connections).compile.drain.start *>
      IO.sleep(5 second) *>
      createCircuit((4, 1, 5), socketGroup, connections).compile.drain.start

  // with stream
  def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use { blocker =>
      SocketGroup[IO](blocker).use { socketGroup =>
        // implicit val cachedInstance = AES128CTR.genEncryptor[IO]
        implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
          val zeros                      = Array.fill(16)(0: Byte)
          def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
          def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
        }

        for {
          _ <- IO(println(s"Starting app"))
          // connections to first hop router
          connections <- Ref.of[IO, Connections](Map.empty[RouterId, RouterConnection])
          _           <- createCircuits(socketGroup, connections)
          _ <- BlazeServerBuilder[IO]
                .bindHttp(8080, "localhost")
                .withHttpApp(httpApp(socketGroup, connections))
                .resource
                .use(_ => IO.never)
        } yield ()
      }
    }.as(ExitCode.Success)

}

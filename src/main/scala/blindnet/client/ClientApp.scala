// package blindnet.client

// import cats.effect._
// import cats.effect.concurrent._
// import cats.effect.IO._
// import cats.implicits._
// import fs2.io.tcp._
// import tsec.cipher.symmetric._
// import tsec.cipher.symmetric.jca._

// object ClientApp extends IOApp {

//   import Client._

//   def run(args: List[String]): IO[ExitCode] =
//     Blocker[IO].use { blocker =>
//       SocketGroup[IO](blocker).use { socketGroup =>
//         implicit val cachedInstance = AES128CTR.genEncryptor[IO]
//         // implicit val ctrStrategy: IvGen[IO, AES128CTR] = JCAIvGen.emptyIv[IO, AES128CTR]
//         implicit val ctrStrategy: IvGen[IO, AES128CTR] = new IvGen[IO, AES128CTR] {
//           val zeros                      = Array.fill(16)(0: Byte)
//           def genIv: IO[Iv[AES128CTR]]   = IO.pure(Iv[AES128CTR](zeros))
//           def genIvUnsafe: Iv[AES128CTR] = Iv[AES128CTR](zeros)
//         }

//         for {
//           _ <- IO(println(s"Starting client"))
//           // connections to first hop router
//           connections    <- Ref.of[IO, Connections](Map.empty[RouterId, RouterConnection])
//           createdPromise <- MVar.empty[IO, (RouterId, CircuitId)]
//           // TORO: return CompleteCircuit
//           _ <- createCircuit(socketGroup, connections, createdPromise).compile.drain
//         } yield ()
//       }
//     }.as(ExitCode.Success)
// }

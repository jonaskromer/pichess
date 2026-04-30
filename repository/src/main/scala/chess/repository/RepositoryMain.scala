package chess.repository

import zio.*
import zio.http.*

/** Standalone entry point for the repository microservice.
  *
  * Run with `sbt "repository/run"` or via Docker. Backed by
  * [[InMemoryGameRepository]]; swap to a persistent impl by changing the
  * `GameRepository` layer provided in `serve`.
  */
object RepositoryMain extends ZIOAppDefault:

  private[repository] val defaultPort = 8091

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    portFromEnv.flatMap(serve)

  /** Read `REPOSITORY_PORT` via the ZIO system service so tests can swap it out
    * with `TestSystem.putEnv` instead of relying on the JVM's real env.
    */
  private[repository] def portFromEnv: Task[Int] =
    zio.System.env("REPOSITORY_PORT").map(parsePort)

  private[repository] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)

  private[repository] def serve(port: Int): Task[Unit] =
    (for
      repo <- ZIO.service[GameRepository]
      _ <- Console.printLine(s"pichess-repository listening on 0.0.0.0:$port")
      _ <- Server.serve(RepositoryServer.routes(repo))
    yield ()).provide(
      InMemoryGameRepository.layer,
      Server.defaultWithPort(port)
    )

package chess.repository

import zio.*
import zio.http.*

/** Standalone entry point for the repository microservice.
  *
  * Run with `sbt "repository/run"` or via Docker. Backed by
  * [[InMemoryGameRepository]]; swap to a persistent impl by changing the
  * `GameRepository` layer provided in `run`.
  */
object RepositoryMain extends ZIOAppDefault:

  private val defaultPort = 8091

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    val port = sys.env.get("REPOSITORY_PORT").flatMap(_.toIntOption).getOrElse(defaultPort)
    (for
      repo <- ZIO.service[GameRepository]
      _    <- Console.printLine(s"pichess-repository listening on 0.0.0.0:$port")
      _    <- Server.serve(RepositoryServer.routes(repo))
    yield ()).provide(
      InMemoryGameRepository.layer,
      Server.defaultWithPort(port),
    )

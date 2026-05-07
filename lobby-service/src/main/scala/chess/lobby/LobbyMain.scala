package chess.lobby

import chess.persistence.{BackendConfig, LobbyRepository}
import chess.persistence.runtime.PersistenceLayers
import zio.*
import zio.http.*

object LobbyMain extends ZIOAppDefault:

  private[lobby] val defaultPort = 8092

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      port <- portFromEnv
      cfg  <- BackendConfig.fromEnv
      _    <- Console.printLine(
                s"pichess-lobby-service backend=${cfg.backend} cache=${cfg.cache}"
              )
      _    <- serve(port).provide(
                lobbyRepoLayer(cfg),
                LobbyService.layer
              )
    yield ()

  private[lobby] def portFromEnv: Task[Int] =
    zio.System.env("LOBBY_PORT").map(parsePort)

  private[lobby] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)

  private[lobby] def lobbyRepoLayer(
      cfg: BackendConfig
  ): TaskLayer[LobbyRepository] =
    PersistenceLayers.lobbyRepository(cfg)

  private[lobby] def serve(
      port: Int
  ): ZIO[LobbyService, Throwable, Unit] =
    val program: ZIO[LobbyService & Server, Throwable, Unit] =
      for
        svc <- ZIO.service[LobbyService]
        _   <- Console.printLine(
                 s"pichess-lobby-service listening on 0.0.0.0:$port"
               )
        _   <- Server.serve(LobbyServer.routes(svc))
      yield ()

    program.provideSomeLayer[LobbyService](Server.defaultWithPort(port))

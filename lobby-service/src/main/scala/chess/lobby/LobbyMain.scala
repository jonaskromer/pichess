package chess.lobby

import chess.obs.{MetricsHttpServer, ProfilerLayer, TracingLayer, TracingMiddleware}
import chess.persistence.{BackendConfig, LobbyRepository}
import chess.persistence.runtime.PersistenceLayers
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

object LobbyMain extends ZIOAppDefault:

  private[lobby] val defaultPort = 8092
  private val defaultMetricsPort = 9104

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap(
      "lobby-service",
      for
        port <- portFromEnv
        cfg  <- BackendConfig.fromEnv
        _    <- Console.printLine(
                  s"pichess-lobby-service backend=${cfg.backend} cache=${cfg.cache}"
                )
        _    <- serve(port).provide(
                  lobbyRepoLayer(cfg),
                  GatewayCoordinator.live,
                  LobbyService.layer
                )
      yield ()
    )

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
    val program: ZIO[
      LobbyService & Server & Tracing & ContextStorage,
      Throwable,
      Unit,
    ] =
      for
        svc         <- ZIO.service[LobbyService]
        metricsPort <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        _           <- Console.printLine(
                         s"pichess-lobby-service listening on 0.0.0.0:$port " +
                           s"(metrics on 0.0.0.0:$metricsPort/metrics)"
                       )
        _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
        // No CORS middleware: the lobby-service is reached exclusively
        // via the gateway's `/lobbies/*` reverse proxy
        // (`chess.controller.LobbyProxy`). Every request the lobby sees
        // is server-to-server, so there's no browser CORS check to satisfy.
        _           <- Server.serve(
                         LobbyServer.routes(svc) @@ TracingMiddleware.serverSpan
                       )
      yield ()

    program.provideSomeLayer[LobbyService](
      Server.defaultWithPort(port) ++ TracingLayer.fromEnv("lobby-service")
    )

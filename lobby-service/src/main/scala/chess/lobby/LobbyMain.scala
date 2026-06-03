package chess.lobby

import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

import chess.obs.{MetricsHttpServer, MetricsLayer, ProfilerLayer, TracingLayer, TracingMiddleware}
import chess.persistence.runtime.PersistenceLayers
import chess.persistence.{BackendConfig, LobbyRepository}

object LobbyMain extends ZIOAppDefault:

  private[lobby] val defaultPort = 8092
  private val defaultMetricsPort = 9104

  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.enableRuntimeMetrics

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
                  LobbyService.layer,
                  TracingLayer.fromEnv("lobby-service")
                )
      yield ()
    )

  private[lobby] def portFromEnv: Task[Int] =
    zio.System.env("LOBBY_PORT").map(parsePort)

  private[lobby] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)

  private[lobby] def lobbyRepoLayer(
      cfg: BackendConfig
  ): ZLayer[Tracing, Throwable, LobbyRepository] =
    PersistenceLayers.lobbyRepository(cfg)

  private[lobby] def serve(
      port: Int
  ): ZIO[LobbyService & Tracing & ContextStorage, Throwable, Unit] =
    val program: ZIO[
      LobbyService & Server & Tracing & ContextStorage & Scope,
      Throwable,
      Unit,
    ] =
      for
        _           <- MetricsLayer.jvmMetricsBootstrap
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

    ZIO.scoped {
      program.provideSomeLayer[
        LobbyService & Tracing & ContextStorage & Scope
      ](
        Server.defaultWithPort(port)
      )
    }

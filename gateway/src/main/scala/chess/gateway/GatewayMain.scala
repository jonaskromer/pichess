package chess.gateway

import io.grpc.ManagedChannelBuilder
import pichess.game_service.ZioGameService
import scalapb.zio_grpc.ZManagedChannel
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

import chess.controller.{
  AnalyticsRelay,
  AnalyticsRoutes,
  AnnotationCache,
  LobbyProxy,
  SessionRegistry,
  SpectatorPresence,
  StackInfo,
  WebController
}
import chess.obs.{MetricsHttpServer, MetricsLayer, ProfilerLayer, TracingLayer, TracingMiddleware}

/** Standalone entry point for the gateway microservice.
  *
  * Hosts the public HTTP surface (Tapir REST + SSE + web-ui static) on
  * `HTTP_PORT` (default 8090). All game commands are forwarded to
  * gameService via a gRPC client opened against `GAME_SERVICE_GRPC`
  * (default `localhost:9000`). The gateway holds **no** authoritative
  * state and **no** per-process "active game" — every request carries
  * its own `gameId` in the URL, so multiple games run side by side.
  */
object GatewayMain extends ZIOAppDefault:

  private val defaultHttpPort = 8090
  private val defaultGameServiceTarget = "localhost:9000"
  private val defaultMetricsPort = 9101

  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.enableRuntimeMetrics

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap(
      "gateway",
      for
        httpPort <- portFromEnv("HTTP_PORT", defaultHttpPort)
        target   <- targetFromEnv
        _        <- serve(httpPort, target)
      yield ()
    )

  private[gateway] def parsePort(envValue: Option[String], default: Int): Int =
    envValue.flatMap(_.toIntOption).getOrElse(default)

  private def portFromEnv(name: String, default: Int): Task[Int] =
    zio.System.env(name).map(parsePort(_, default))

  private def targetFromEnv: Task[String] =
    zio.System
      .env("GAME_SERVICE_GRPC")
      .map(_.filter(_.trim.nonEmpty).getOrElse(defaultGameServiceTarget))

  private def serve(httpPort: Int, target: String): Task[Unit] =
    val program: ZIO[
      ZioGameService.GameServiceClient & Server & Client
        & Tracing & ContextStorage & Scope,
      Throwable,
      Unit
    ] =
      for
        // Start the JVM metric trackers so heap / GC / thread counters
        // flow into the Prometheus publisher used by /metrics. Built
        // in the surrounding ZIO.scoped block so the trackers run
        // for the lifetime of this service rather than per-request.
        _            <- MetricsLayer.jvmMetricsBootstrap
        client       <- ZIO.service[ZioGameService.GameServiceClient]
        registry     <- SessionRegistry.make
        cache        <- AnnotationCache.make
        presence     <- SpectatorPresence.make
        lobbyBaseUrl <- LobbyProxy.baseUrlFromEnv
        stackInfo    <- StackInfo.fromEnv
        // Optional: enables the Lichess spectate bridge (POST /lichess/games)
        // when a bot token is present. Absent → the route is simply not added.
        lichessToken <- zio.System.env("LICHESS_BOT_TOKEN").map(_.filter(_.nonEmpty))
        metricsPort  <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        // Live-analytics loop-back: fan `chess.analytics` (Spark speed-layer
        // output) out to SSE clients. Only started when Kafka is configured,
        // so the gateway still runs in Kafka-less setups.
        analyticsHub <- Hub.bounded[String](256)
        kafkaBoot    <- zio.System.env("KAFKA_BOOTSTRAP_SERVERS").map(_.filter(_.trim.nonEmpty))
        _            <- ZIO.foreachDiscard(kafkaBoot) { servers =>
                          AnalyticsRelay
                            .run(analyticsHub)
                            .provideLayer(AnalyticsRelay.consumerLayer(servers))
                            .catchAllCause(c => ZIO.logWarningCause("analytics relay stopped", c))
                            .forkDaemon
                        }
        _            <- Console.printLine(
                          s"pichess-gateway HTTP listening on 0.0.0.0:$httpPort " +
                            s"(game-service=$target, lobby-service=$lobbyBaseUrl, " +
                            s"stack=${stackInfo.backend}" +
                            (if stackInfo.extras.isEmpty then ""
                             else s"+${stackInfo.extras.mkString(",")}") + ")"
                        )
        _            <- Console.printLine(
                          s"pichess-gateway metrics on 0.0.0.0:$metricsPort/metrics"
                        )
        _            <- MetricsHttpServer.serve(metricsPort).forkDaemon
        _            <- Server.install(
                          (WebController.routes(
                            client,
                            registry,
                            cache,
                            presence,
                            lobbyBaseUrl,
                            stackInfo,
                            lichessToken
                          ) ++ AnalyticsRoutes.routes(analyticsHub)) @@ TracingMiddleware.serverSpan
                        )
        // Run forever; the gateway is no longer killable from a network
        // request — `docker stop` / SIGTERM is the only shutdown path.
        _            <- ZIO.never
      yield ()

    // Raw gRPC client → tracing decorator. The decorator's layer expects
    // a `GameServiceClient` and a `Tracing` in env; piping the raw client
    // layer in via `>>>` substitutes the env-resident GameServiceClient
    // with the traced one for everything downstream (WebController, SSE,
    // …). `Tracing` flows through unchanged and is satisfied by
    // `TracingLayer.fromEnv`.
    val rawClientLayer =
      ZioGameService.GameServiceClient.live(
        ZManagedChannel(
          ManagedChannelBuilder.forTarget(target).usePlaintext()
        )
      )
    val tracedClientLayer = rawClientLayer >>> TracingGameServiceClient.layer

    ZIO.scoped {
      program.provideSome[Scope](
        Server.defaultWithPort(httpPort),
        Client.default,
        tracedClientLayer,
        TracingLayer.fromEnv("gateway"),
      )
    }

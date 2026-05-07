package chess.gateway

import chess.controller.{AnnotationCache, SessionRegistry, WebController}
import io.grpc.ManagedChannelBuilder
import pichess.game_service.ZioGameService
import scalapb.zio_grpc.ZManagedChannel
import zio.*
import zio.http.*

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

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      httpPort <- portFromEnv("HTTP_PORT", defaultHttpPort)
      target   <- targetFromEnv
      _        <- serve(httpPort, target)
    yield ()

  private[gateway] def parsePort(envValue: Option[String], default: Int): Int =
    envValue.flatMap(_.toIntOption).getOrElse(default)

  private def portFromEnv(name: String, default: Int): Task[Int] =
    zio.System.env(name).map(parsePort(_, default))

  private def targetFromEnv: Task[String] =
    zio.System
      .env("GAME_SERVICE_GRPC")
      .map(_.filter(_.trim.nonEmpty).getOrElse(defaultGameServiceTarget))

  private def serve(httpPort: Int, target: String): Task[Unit] =
    val program: ZIO[ZioGameService.GameServiceClient & Server, Throwable, Unit] =
      for
        client   <- ZIO.service[ZioGameService.GameServiceClient]
        registry <- SessionRegistry.make
        cache    <- AnnotationCache.make
        _        <- Console.printLine(
                      s"pichess-gateway HTTP listening on 0.0.0.0:$httpPort (game-service=$target)"
                    )
        _        <- Server.install(
                      WebController.routes(client, registry, cache)
                    )
        // Run forever; the gateway is no longer killable from a network
        // request — `docker stop` / SIGTERM is the only shutdown path.
        _      <- ZIO.never
      yield ()

    program.provide(
      Server.defaultWithPort(httpPort),
      ZioGameService.GameServiceClient.live(
        ZManagedChannel(
          ManagedChannelBuilder.forTarget(target).usePlaintext()
        )
      )
    )

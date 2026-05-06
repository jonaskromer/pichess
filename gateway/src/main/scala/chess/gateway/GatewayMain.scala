package chess.gateway

import chess.controller.WebController
import io.grpc.ManagedChannelBuilder
import pichess.game_service.{NewGameRequest, ZioGameService}
import scalapb.zio_grpc.ZManagedChannel
import zio.*
import zio.http.*
import zio.stream.SubscriptionRef

/** Standalone entry point for the gateway microservice.
  *
  * Hosts the public HTTP surface (Tapir REST + SSE + web-ui static) on
  * `HTTP_PORT` (default 8090). All game commands are forwarded to gameService
  * via a gRPC client opened against `GAME_SERVICE_GRPC` (default
  * `localhost:9000`). Holds **no** authoritative game state — the only piece
  * of mutable per-process state is `activeGameId`, which tracks "the game
  * this gateway process is currently looking at".
  *
  * On startup we call `NewGame` once to seed `activeGameId`. `/api/new` and
  * `/api/load` re-seed it; the SSE source re-subscribes when it changes.
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
        client       <- ZIO.service[ZioGameService.GameServiceClient]
        initial      <- client.newGame(NewGameRequest())
        _            <- Console.printLine(
                          s"pichess-gateway HTTP listening on 0.0.0.0:$httpPort (game-service=$target, initial gameId=${initial.gameId})"
                        )
        activeGameId <- SubscriptionRef.make(initial.gameId)
        shutdown     <- Promise.make[Nothing, Unit]
        _            <- Server
                          .install(WebController.routes(client, activeGameId, shutdown))
        _            <- shutdown.await.race(ZIO.never)
      yield ()

    program.provide(
      Server.defaultWithPort(httpPort),
      ZioGameService.GameServiceClient.live(
        ZManagedChannel(
          ManagedChannelBuilder.forTarget(target).usePlaintext()
        )
      )
    )

package chess.gateway

import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import pichess.game_service.{
  AnalyzeRequest,
  ExportRequest,
  GameIdRequest,
  ListActiveGamesRequest,
  LoadGameRequest,
  MoveRequest,
  NewGameRequest,
  ZioGameService
}
import scalapb.zio_grpc.{ServerLayer, ZManagedChannel}
import zio.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import chess.events.InMemoryGameEventProducer
import chess.gameservice.{GameSessions, GrpcServer}
import chess.obs.TracingLayer
import chess.persistence.InMemoryGameRepository
import chess.service.GameServiceLive

/** Coverage spec for the tracing-decorator over `GameServiceClient`.
  *
  * The decorator is purely pass-through plus span instrumentation, so the tests
  * just exercise every rpc method against a real in-process `GameService`
  * backed by the in-memory repo. We assert that the decorator behaves
  * identically to the underlying client — the actual span emission is verified
  * end-to-end by the Jaeger spans the perf suite captures (the decorator's
  * behaviour is statically obvious; what the tests prove is that we didn't
  * break the rpc forwarding).
  */
object TracingGameServiceClientSpec extends ZIOSpecDefault:

  private def stackLayer(name: String) =
    ZLayer.make[
      ZioGameService.GameServiceClient & scalapb.zio_grpc.Server & Tracing &
        ContextStorage
    ](
      InMemoryGameRepository.layer,
      GameSessions.layer,
      GameServiceLive.layer,
      InMemoryGameEventProducer.layer,
      TracingLayer.noop,
      GrpcServer.asServiceLayer,
      // Vs-bot deps — required by GrpcServer's layer; harmless for
      // these tracing-only tests because vs_bot defaults to false.
      chess.service.BotConfigRepository.inMemoryLayer,
      chess.bot.engine.EngineLayer.live,
      ServerLayer.fromEnvironment[ZioGameService.RCGameService](
        InProcessServerBuilder.forName(name).directExecutor()
      ),
      ZioGameService.GameServiceClient.live(
        ZManagedChannel(
          InProcessChannelBuilder.forName(name).directExecutor()
        ),
        options = io.grpc.CallOptions.DEFAULT
      )
    )

  /** Build a fresh in-process gRPC stack + the tracing-decorated client. Each
    * test gets its own channel name so the inprocess registry doesn't collide.
    */
  private def withTracedClient[A](
      body: TracingGameServiceClient => ZIO[
        Tracing & ContextStorage,
        Throwable,
        A
      ]
  ): ZIO[Any, Throwable, A] =
    for
      name <- ZIO.succeed(s"tracing-spec-${java.util.UUID.randomUUID()}")
      out <- ZIO.scoped {
        (for
          raw <- ZIO.service[ZioGameService.GameServiceClient]
          tracing <- ZIO.service[Tracing]
          client = new TracingGameServiceClient(raw, tracing)
          result <- body(client)
        yield result).provideSomeLayer[Scope](stackLayer(name))
      }
    yield out

  def spec = suite("TracingGameServiceClient")(
    test("newGame → underlying newGame, returns state") {
      withTracedClient { client =>
        for reply <- client.newGame(NewGameRequest())
        yield assertTrue(reply.fen.nonEmpty)
      }
    },
    test("loadGame → underlying loadGame, returns state") {
      // GameServiceLive.loadGame accepts FEN/JSON/PGN.
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      withTracedClient { client =>
        for reply <- client.loadGame(LoadGameRequest(fen))
        yield assertTrue(reply.fen.nonEmpty)
      }
    },
    test("makeMove → underlying makeMove, returns updated state") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          reply <- client.makeMove(MoveRequest(started.gameId, "e2 e4"))
        yield assertTrue(reply.fen.startsWith("rnbqkbnr/pppppppp/8/8/4P3"))
      }
    },
    test("undo → underlying undo") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          _ <- client.makeMove(MoveRequest(started.gameId, "e2 e4"))
          reply <- client.undo(GameIdRequest(started.gameId))
        yield assertTrue(reply.fen.nonEmpty)
      }
    },
    test("redo → underlying redo") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          _ <- client.makeMove(MoveRequest(started.gameId, "e2 e4"))
          _ <- client.undo(GameIdRequest(started.gameId))
          reply <- client.redo(GameIdRequest(started.gameId))
        yield assertTrue(reply.fen.startsWith("rnbqkbnr/pppppppp/8/8/4P3"))
      }
    },
    test("claimDraw → underlying claimDraw (rejected on fresh game)") {
      // GameServiceLive.claimDraw rejects when neither 50-move nor
      // threefold conditions hold; we just need to exercise the
      // decorator's forwarding so a failing rpc is fine.
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          exit <- client.claimDraw(GameIdRequest(started.gameId)).exit
        yield assertTrue(exit.isFailure)
      }
    },
    test("forfeit → underlying forfeit") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          reply <- client.forfeit(GameIdRequest(started.gameId))
        yield assertTrue(reply.fen.nonEmpty)
      }
    },
    test("getState → underlying getState, returns persisted state") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          reply <- client.getState(GameIdRequest(started.gameId))
        yield assertTrue(reply.gameId == started.gameId)
      }
    },
    test("replayGame → underlying replayGame, returns the position history") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          reply <- client.replayGame(GameIdRequest(started.gameId))
        yield assertTrue(
          reply.gameId == started.gameId,
          reply.frames.nonEmpty // at least the initial-position frame
        )
      }
    },
    test("exportGame → underlying exportGame, returns the requested format") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          reply <- client.exportGame(ExportRequest(started.gameId, "fen"))
        yield assertTrue(reply.body.nonEmpty)
      }
    },
    test("analyzeGame → underlying analyzeGame, returns analysis JSON") {
      withTracedClient { client =>
        for reply <- client.analyzeGame(AnalyzeRequest("1. e4 e5 *", 1))
        yield assertTrue(reply.analysisJson.contains("opening"))
      }
    },
    test(
      "listActiveGames → underlying listActiveGames, includes the new game"
    ) {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          reply <- client.listActiveGames(ListActiveGamesRequest())
        yield assertTrue(reply.games.exists(_.gameId == started.gameId))
      }
    },
    test("subscribeGame → underlying subscribeGame (single state emission)") {
      withTracedClient { client =>
        for
          started <- client.newGame(NewGameRequest())
          first <- client.subscribeGame(GameIdRequest(started.gameId)).runHead
        yield assertTrue(first.exists(_.gameId == started.gameId))
      }
    },
    test(
      "withResponseMetadata exposes the underlying response-metadata variant"
    ) {
      withTracedClient { client =>
        ZIO.succeed(assertTrue(client.withResponseMetadata != null))
      }
    },
    test("transform preserves the tracing decoration") {
      // Apply an identity ClientTransform; the returned client should
      // still be a TracingGameServiceClient. `ClientTransform` is
      // `ZTransform[ClientCallContext, ClientCallContext]`, built via
      // `ZTransform.apply(ctx => ZIO.succeed(ctx))`.
      val identityTransform: scalapb.zio_grpc.ClientTransform =
        scalapb.zio_grpc.ZTransform { ctx =>
          ZIO.succeed(ctx)
        }
      withTracedClient { client =>
        ZIO.succeed {
          val transformed = client.transform(identityTransform)
          assertTrue(transformed.isInstanceOf[TracingGameServiceClient])
        }
      }
    },
    test("metadataCarrier.set writes the key/value into the gRPC Metadata") {
      // Exercise the carrier directly — under TracingLayer.noop the
      // injector never has a span to propagate, so `set` is otherwise
      // unreachable from the smoke tests above.
      withTracedClient { client =>
        ZIO.succeed {
          val md = new io.grpc.Metadata()
          val carrier = client.metadataCarrier(md)
          carrier.set(md, "traceparent", "00-trace-span-01")
          val key = io.grpc.Metadata.Key.of(
            "traceparent",
            io.grpc.Metadata.ASCII_STRING_MARSHALLER
          )
          assertTrue(md.get(key) == "00-trace-span-01")
        }
      }
    },
    test("TracingGameServiceClient.layer wires the decorator") {
      val program =
        for client <- ZIO.service[ZioGameService.GameServiceClient]
        yield assertTrue(client.isInstanceOf[TracingGameServiceClient])

      val name = s"tracing-layer-${java.util.UUID.randomUUID()}"
      // `>>>` chains stackLayer into the decorator layer, dropping the
      // raw GameServiceClient from the output and replacing it with
      // the decorated one. Without it the env would carry both
      // bindings and ZLayer raises an ambiguity error.
      ZIO.scoped {
        program.provideSomeLayer[Scope](
          stackLayer(name) >>> TracingGameServiceClient.layer
        )
      }
    }
  ) @@ TestAspect.withLiveClock

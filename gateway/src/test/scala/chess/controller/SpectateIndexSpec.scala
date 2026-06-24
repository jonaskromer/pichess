package chess.controller

import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import pichess.game_service.{ActiveGame, NewGameRequest, ZioGameService}
import scalapb.zio_grpc.{ServerLayer, ZManagedChannel}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import chess.api.OngoingGame
import chess.events.InMemoryGameEventProducer
import chess.gameservice.{GameSessions, GrpcServer}
import chess.obs.TracingLayer
import chess.persistence.InMemoryGameRepository
import chess.service.GameServiceLive

/** `SpectateIndex`: the pure spectator-rule projection ([[toOngoingNative]]),
  * the tournament fan-out ([[tournamentGames]] against a fake NowChess), the
  * Lichess `account/playing` projection ([[toOngoingLichess]] /
  * [[lichessGames]] against a fake Lichess), and the `GET /spectate/games`
  * route (native source + per-source tolerance).
  */
object SpectateIndexSpec extends ZIOSpecDefault:

  /** A NowChess base nothing listens on, so the tournament source is tolerated.
    */
  private val deadBase = "http://127.0.0.1:1"

  // -- in-process gRPC stack (same pattern as TracingGameServiceClientSpec) ---
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
      chess.service.BotConfigRepository.inMemoryLayer,
      chess.bot.engine.EngineLayer.live,
      ServerLayer.fromEnvironment[ZioGameService.RCGameService](
        InProcessServerBuilder.forName(name).directExecutor()
      ),
      ZioGameService.GameServiceClient.live(
        ZManagedChannel(InProcessChannelBuilder.forName(name).directExecutor()),
        options = io.grpc.CallOptions.DEFAULT
      )
    )

  private def withClient[A](
      body: ZioGameService.GameServiceClient => ZIO[
        Client & Scope,
        Throwable,
        A
      ]
  ): ZIO[Any, Throwable, A] =
    for
      name <- ZIO.succeed(s"spectate-spec-${java.util.UUID.randomUUID()}")
      out <- ZIO.scoped {
        (for
          client <- ZIO.service[ZioGameService.GameServiceClient]
          out <- body(client)
        yield out).provideSomeLayer[Scope](stackLayer(name) ++ Client.default)
      }
    yield out

  /** A client pointed at an in-process channel with no server, so every rpc
    * fails — exercises the native-source tolerance.
    */
  private def withDeadClient[A](
      body: ZioGameService.GameServiceClient => ZIO[
        Client & Scope,
        Throwable,
        A
      ]
  ): ZIO[Any, Throwable, A] =
    for
      name <- ZIO.succeed(s"dead-${java.util.UUID.randomUUID()}")
      out <- ZIO.scoped {
        (for
          client <- ZIO.service[ZioGameService.GameServiceClient]
          out <- body(client)
        yield out).provideSomeLayer[Scope](
          ZioGameService.GameServiceClient.live(
            ZManagedChannel(
              InProcessChannelBuilder.forName(name).directExecutor()
            )
          ) ++ Client.default
        )
      }
    yield out

  // -- fake NowChess upstream for the tournament fan-out ----------------------
  private val fakeNowChess: Routes[Any, Response] = Routes(
    Method.GET / "api" / "tournament" ->
      handler(Response.json("""{"started":[{"id":"t1"}]}""")),
    Method.GET / "api" / "tournament" / "t1" ->
      handler(Response.json("""{"round":1}""")),
    Method.GET / "api" / "tournament" / "t1" / "round" / "1" ->
      handler(
        Response.json(
          """{"pairings":[{"matches":[{"gameId":"g1"},{"gameId":"g2"}]}]}"""
        )
      ),
    Method.GET / "api" / "tournament" / "t1" / "game" / "g1" ->
      handler(
        Response.json(
          """{"status":"ongoing","white":{"name":"Alice"},"black":{"name":"Bob"}}"""
        )
      ),
    Method.GET / "api" / "tournament" / "t1" / "game" / "g2" ->
      handler(
        Response.json(
          """{"status":"checkmate","white":{"name":"C"},"black":{"name":"D"}}"""
        )
      )
  )

  /** A NowChess whose top-level list is malformed — exercises the decode-error
    * path of [[SpectateIndex.getJson]].
    */
  private val fakeBadNowChess: Routes[Any, Response] = Routes(
    Method.GET / "api" / "tournament" ->
      handler(Response.json("""{"nope":true}"""))
  )

  /** Our bot's control API reporting it is playing tournament `t1`. */
  private val fakeBotControl: Routes[Any, Response] = Routes(
    Method.GET / "control" / "tournaments" ->
      handler(Response.json("""{"active":["t1"]}"""))
  )

  /** Our bot's control API reporting it is in no tournaments. */
  private val fakeBotControlEmpty: Routes[Any, Response] = Routes(
    Method.GET / "control" / "tournaments" ->
      handler(Response.json("""{"active":[]}"""))
  )

  /** A fake Lichess serving the bot's `nowPlaying` games. */
  private val fakeLichess: Routes[Any, Response] = Routes(
    Method.GET / "api" / "account" / "playing" ->
      handler(
        Response.json(
          """{"nowPlaying":[{"gameId":"lg1","color":"white","opponent":{"username":"Magnus"}}]}"""
        )
      )
  )

  private def serveWith[A](routes: Routes[Any, Response])(
      body: String => ZIO[Scope & Client, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO
      .scoped {
        val serverLayer =
          ZLayer.succeed(Server.Config.default.port(0)) >>> Server.live
        ZIO
          .serviceWithZIO[Server] { srv =>
            for
              _ <- srv.install(routes)
              port <- srv.port
              out <- body(s"http://localhost:$port")
            yield out
          }
          .provideSomeLayer[Scope & Client](serverLayer)
      }
      .provide(Client.default)

  private def withUpstream[A](
      body: String => ZIO[Scope & Client, Throwable, A]
  ): ZIO[Any, Throwable, A] = serveWith(fakeNowChess)(body)

  def spec = suite("SpectateIndex")(
    suite("toOngoingNative (spectator rules)")(
      test("PvP, no policy → listed, unrestricted, generic labels") {
        val r = SpectateIndex.toOngoingNative(
          ActiveGame(gameId = "g1", vsBot = false, botSide = ""),
          SpectatorInfo(None, 0)
        )
        assertTrue(
          r == Some(
            OngoingGame(
              "g1",
              "pvp",
              "White",
              "Black",
              "ongoing",
              0,
              0,
              true,
              None
            )
          )
        )
      },
      test("PvBot with the bot on white labels the sides") {
        val r = SpectateIndex.toOngoingNative(
          ActiveGame("g2", vsBot = true, botSide = "white"),
          SpectatorInfo(None, 0)
        )
        assertTrue(
          r.exists(o =>
            o.gameType == "pvbot" && o.white == "piChess (bot)" && o.black == "Player"
          )
        )
      },
      test("PvBot with the bot on black labels the sides") {
        val r = SpectateIndex.toOngoingNative(
          ActiveGame("g3", vsBot = true, botSide = "black"),
          SpectatorInfo(None, 0)
        )
        assertTrue(
          r.exists(o => o.white == "Player" && o.black == "piChess (bot)")
        )
      },
      test("host disallowed spectating → omitted") {
        val r = SpectateIndex.toOngoingNative(
          ActiveGame("g4", vsBot = false, botSide = ""),
          SpectatorInfo(
            Some(SpectatorPolicy(allowSpectate = false, limit = 0)),
            0
          )
        )
        assertTrue(r.isEmpty)
      },
      test("full → listed but not spectateable") {
        val r = SpectateIndex.toOngoingNative(
          ActiveGame("g5", vsBot = false, botSide = ""),
          SpectatorInfo(
            Some(SpectatorPolicy(allowSpectate = true, limit = 2)),
            2
          )
        )
        assertTrue(
          r.exists(o => o.limit == 2 && o.spectators == 2 && !o.spectateable)
        )
      },
      test("under the limit → spectateable") {
        val r = SpectateIndex.toOngoingNative(
          ActiveGame("g6", vsBot = false, botSide = ""),
          SpectatorInfo(
            Some(SpectatorPolicy(allowSpectate = true, limit = 2)),
            1
          )
        )
        assertTrue(r.exists(_.spectateable))
      }
    ),
    suite("tournamentGames (NowChess fan-out)")(
      test("lists ongoing tournament games and filters finished ones") {
        withUpstream { base =>
          for games <- SpectateIndex.tournamentGames(base)
          yield assertTrue(
            games == List(
              OngoingGame(
                "g1",
                "tournament",
                "Alice",
                "Bob",
                "ongoing",
                0,
                0,
                true,
                Some("t1")
              )
            )
          )
        }
      },
      test("a malformed upstream body surfaces a decode error") {
        serveWith(fakeBadNowChess) { base =>
          SpectateIndex.tournamentGames(base).either.map {
            case Left(e) =>
              assertTrue(e.getMessage.contains("decode /api/tournament"))
            case Right(_) => assertTrue(false)
          }
        }
      }
    ),
    suite("tournamentGamesScoped (scope=ours, via the bot service)")(
      test("keeps only our bot's ongoing games in the tournaments we're in") {
        // One server stands in for both upstreams (paths don't collide):
        // /control/tournaments → ["t1"], and the NowChess fan for t1.
        serveWith(fakeNowChess ++ fakeBotControl) { base =>
          for
            mine    <- SpectateIndex.tournamentGamesScoped(base, base, "Alice")
            notMine <- SpectateIndex.tournamentGamesScoped(base, base, "Zzz")
          yield assertTrue(
            // g1 (Alice vs Bob, ongoing) kept; g2 (finished) dropped.
            mine.map(_.id) == List("g1"),
            mine.forall(g => g.white == "Alice" || g.black == "Alice"),
            // A name we don't play under filters everything out — proving the
            // our-bot filter, not just the ongoing filter, does the work.
            notMine.isEmpty
          )
        }
      },
      test("no active tournaments → empty, without calling the external server") {
        // NowChess base is dead: were the empty-active short-circuit not to
        // fire, the fan-out would hit it and fail. An empty list proves we
        // never reached out.
        serveWith(fakeBotControlEmpty) { botBase =>
          for games <- SpectateIndex
              .tournamentGamesScoped(deadBase, botBase, "pichess")
          yield assertTrue(games.isEmpty)
        }
      }
    ),
    suite("toOngoingLichess (account/playing projection)")(
      test("bot on white → opponent username on black") {
        val r = SpectateIndex.toOngoingLichess(
          SpectateIndex.LiNowPlaying(
            "lg1",
            "white",
            SpectateIndex.LiOpponent(Some("Alice"), None)
          )
        )
        assertTrue(
          r == OngoingGame(
            "lg1",
            "lichess",
            "piChess (bot)",
            "Alice",
            "ongoing",
            0,
            0,
            true,
            None
          )
        )
      },
      test("bot on black → opponent (by id fallback) on white") {
        val r = SpectateIndex.toOngoingLichess(
          SpectateIndex.LiNowPlaying(
            "lg2",
            "black",
            SpectateIndex.LiOpponent(None, Some("bob"))
          )
        )
        assertTrue(r.white == "bob" && r.black == "piChess (bot)")
      },
      test("no username or id → generic opponent label") {
        val r = SpectateIndex.toOngoingLichess(
          SpectateIndex.LiNowPlaying(
            "lg3",
            "white",
            SpectateIndex.LiOpponent(None, None)
          )
        )
        assertTrue(r.black == "opponent")
      }
    ),
    suite("lichessGames (account/playing source)")(
      test("no token → no rows, no I/O") {
        (for games <- SpectateIndex.lichessGames(deadBase, None)
        yield assertTrue(games.isEmpty)).provide(Client.default)
      },
      test("with a token → the bot's live games as lichess rows") {
        serveWith(fakeLichess) { base =>
          for games <- SpectateIndex.lichessGames(base, Some("tok"))
          yield assertTrue(
            games == List(
              OngoingGame(
                "lg1",
                "lichess",
                "piChess (bot)",
                "Magnus",
                "ongoing",
                0,
                0,
                true,
                None
              )
            )
          )
        }
      }
    ),
    suite("GET /spectate/games")(
      test(
        "lists native game-service games (tournament source tolerated when down)"
      ) {
        withClient { client =>
          for
            presence <- SpectatorPresence.make
            started <- client.newGame(NewGameRequest())
            res <- SpectateIndex
              .routes(client, presence, deadBase, deadBase, "pichess", None)
              .runZIO(Request.get(url"/spectate/games"))
            body <- res.body.asString
            games <- ZIO
              .fromEither(body.fromJson[List[OngoingGame]])
              .mapError(e => new RuntimeException(e))
          yield assertTrue(
            res.status == Status.Ok,
            games.exists(g => g.id == started.gameId && g.gameType == "pvp")
          )
        }
      },
      test("tolerates an unreachable game-service (still 200, just empty)") {
        withDeadClient { client =>
          for
            presence <- SpectatorPresence.make
            res <- SpectateIndex
              .routes(client, presence, deadBase, deadBase, "pichess", None)
              .runZIO(Request.get(url"/spectate/games"))
            body <- res.body.asString
          yield assertTrue(res.status == Status.Ok, body == "[]")
        }
      },
      test("scope=all takes the full external fan-out (tolerated when down)") {
        withClient { client =>
          for
            presence <- SpectatorPresence.make
            started  <- client.newGame(NewGameRequest())
            res <- SpectateIndex
              .routes(client, presence, deadBase, deadBase, "pichess", None)
              .runZIO(Request.get(url"/spectate/games?scope=all"))
            body <- res.body.asString
            games <- ZIO
              .fromEither(body.fromJson[List[OngoingGame]])
              .mapError(e => new RuntimeException(e))
          yield assertTrue(
            res.status == Status.Ok,
            games.exists(g => g.id == started.gameId && g.gameType == "pvp")
          )
        }
      }
    )
  )

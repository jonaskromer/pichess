package chess.controller

import zio.*
import zio.http.*
import zio.test.*

/** `TournamentHistory` relays the repository's tournament archive (history list
  * + detail) and degrades a slow/unreachable repository to a 502/504 rather than
  * hanging — mirroring [[TournamentProxy]]. */
object TournamentHistorySpec extends ZIOSpecDefault:

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
              _    <- srv.install(routes)
              port <- srv.port
              out  <- body(s"http://localhost:$port")
            yield out
          }
          .provideSomeLayer[Scope & Client](serverLayer)
      }
      .provide(Client.default)

  private val fakeRepo: Routes[Any, Response] = Routes(
    Method.GET / "tournament-archives" ->
      handler(
        Response.json(
          """[{"tournamentId":"t1","name":"Cup","format":"swiss","finishedAt":1,"nbPlayers":4,"winner":"pichess"}]"""
        )
      ),
    Method.GET / "tournament-archives" / "t1" ->
      handler(
        Response.json(
          """{"tournamentId":"t1","name":"Cup","format":"swiss","finishedAt":1,"standings":[],"gameIds":["g1"]}"""
        )
      ),
    Method.GET / "archives" / "g1" ->
      handler(
        Response.json(
          """{"gameId":"g1","pgn":"1. e4 e5","white":"pichess","black":"random"}"""
        )
      )
  )

  def spec = suite("TournamentHistory")(
    test("GET /tournament/history relays the repository's archive list") {
      serveWith(fakeRepo) { base =>
        for
          res <- TournamentHistory
            .routes(base)
            .runZIO(Request.get(url"/tournament/history"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.Ok,
          body.contains("\"tournamentId\":\"t1\"")
        )
      }
    },
    test("GET /tournament/archive/{id} relays the detail (ladder + game ids)") {
      serveWith(fakeRepo) { base =>
        for
          res <- TournamentHistory
            .routes(base)
            .runZIO(Request.get(url"/tournament/archive/t1"))
          body <- res.body.asString
        yield assertTrue(res.status == Status.Ok, body.contains("\"gameIds\""))
      }
    },
    test("an unreachable repository becomes a 502/504, not a hang or crash") {
      ZIO
        .scoped {
          TournamentHistory
            .routes("http://127.0.0.1:1")
            .runZIO(Request.get(url"/tournament/history"))
            .map(res =>
              assertTrue(
                res.status == Status.BadGateway ||
                  res.status == Status.GatewayTimeout
              )
            )
        }
        .provide(Client.default)
    },
    test("GET /tournament/game/{gameId} relays the per-game archive") {
      serveWith(fakeRepo) { base =>
        for
          res <- TournamentHistory
            .routes(base)
            .runZIO(Request.get(url"/tournament/game/g1"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.Ok,
          body.contains("\"gameId\":\"g1\"")
        )
      }
    },
    test("a malformed repository URL becomes a 502 (buildUrl Left arm)") {
      ZIO
        .scoped {
          for
            res <- TournamentHistory
              .routes("http://repository with space")
              .runZIO(Request.get(url"/tournament/history"))
            body <- res.body.asString
          yield assertTrue(
            res.status == Status.BadGateway,
            body.contains("invalid URL")
          )
        }
        .provide(Client.default)
    },
    test("a slow repository becomes a 504 (timeoutTo arm)") {
      // Accepts the connection but doesn't answer within the 2 s cap, so the
      // relay takes the GatewayTimeout arm — distinct from the connection-
      // refused BadGateway arm above. Live clock so the real cap actually fires.
      val slowRepo: Routes[Any, Response] = Routes(
        Method.GET / "tournament-archives" ->
          handler(ZIO.sleep(3.seconds).as(Response.json("[]")))
      )
      serveWith(slowRepo) { base =>
        for
          res <- TournamentHistory
            .routes(base)
            .runZIO(Request.get(url"/tournament/history"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.GatewayTimeout,
          body.contains("timed out")
        )
      }
    } @@ TestAspect.withLiveClock,
    test("repositoryUrlFromEnv reads PICHESS_REPOSITORY_URL when set") {
      for
        _ <- TestSystem
          .putEnv(TournamentHistory.EnvRepositoryUrl, "http://repo.test:8091")
        url <- TournamentHistory.repositoryUrlFromEnv
      yield assertTrue(url == "http://repo.test:8091")
    },
    test("repositoryUrlFromEnv falls back to the default when unset") {
      for url <- TournamentHistory.repositoryUrlFromEnv
      yield assertTrue(url == "http://repository:8091")
    }
  )

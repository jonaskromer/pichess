package chess.controller

import zio.*
import zio.http.*
import zio.test.*

/** Tests the tournament proxy against a tiny real upstream on an ephemeral port
  * (one echo server doubling as both the NowChess server and the bot-control
  * API). Each request through the proxy hits it and we assert the round trip.
  */
object TournamentProxySpec extends ZIOSpecDefault:

  /** Echo upstream: any method/path → "METHOD <path>" (the path rendering
    * varies by zio-http version, so tests assert with `contains`).
    */
  private val upstream: Routes[Any, Response] = Routes(
    Method.ANY / trailing -> handler { (rest: Path, req: Request) =>
      Response.text(s"${req.method} ${rest.toString}")
    }
  )

  /** Bring up `routes` on an ephemeral port; yield its base URL. */
  private def withServer[A](routes: Routes[Any, Response])(
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

  /** Bring up the echo upstream on an ephemeral port; yield its base URL. */
  private def withUpstream[A](
      body: String => ZIO[Scope & Client, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    withServer(upstream)(body)

  def spec = suite("TournamentProxy")(
    test("GET /tournament/list relays to the upstream /api/tournament") {
      withUpstream { base =>
        val routes = TournamentProxy.routes(base, base)
        for
          res <- routes.runZIO(Request.get(url"/tournament/list"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.Ok,
          body.startsWith("GET"),
          body.contains("api/tournament")
        )
      }
    },
    test("POST /tournament/{id}/join signals the bot control API") {
      withUpstream { base =>
        val routes = TournamentProxy.routes(base, base)
        for
          res <- routes.runZIO(
            Request.post(url"/tournament/t1/join", Body.empty)
          )
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.Ok,
          body.startsWith("POST"),
          body.contains("control/tournaments/t1")
        )
      }
    },
    test("DELETE /tournament/{id}/join signals a withdraw") {
      withUpstream { base =>
        val routes = TournamentProxy.routes(base, base)
        for
          res <- routes.runZIO(
            Request(method = Method.DELETE, url = url"/tournament/t1/join")
          )
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.Ok,
          body.startsWith("DELETE"),
          body.contains("control/tournaments/t1")
        )
      }
    },
    test("an unreachable upstream becomes 502 (orElseSucceed arm)") {
      val routes =
        TournamentProxy.routes("http://127.0.0.1:1", "http://127.0.0.1:1")
      val program =
        for
          res <- routes.runZIO(Request.get(url"/tournament/list"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.BadGateway,
          body.contains("upstream unreachable")
        )
      program.provide(Client.default, Scope.default)
    },
    test("a malformed base URL becomes 502 (buildUrl Left arm)") {
      val routes = TournamentProxy.routes("http://tournament with space", "x")
      val program =
        for
          res <- routes.runZIO(Request.get(url"/tournament/list"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.BadGateway,
          body.contains("invalid URL")
        )
      program.provide(Client.default, Scope.default)
    },
    test("a slow upstream times out as a 504 (timeoutTo arm)") {
      // An upstream that accepts the connection but never answers within the
      // 2 s cap, so the relay takes the GatewayTimeout arm — distinct from the
      // connection-refused BadGateway arm above. Live clock so the real cap
      // fires (a TestClock would never advance the 2 s timer).
      val slow: Routes[Any, Response] = Routes(
        Method.ANY / trailing ->
          handler(ZIO.sleep(3.seconds).as(Response.text("late")))
      )
      withServer(slow) { base =>
        for
          res <- TournamentProxy
            .routes(base, base)
            .runZIO(Request.get(url"/tournament/list"))
          body <- res.body.asString
        yield assertTrue(
          res.status == Status.GatewayTimeout,
          body.contains("timed out")
        )
      }
    } @@ TestAspect.withLiveClock,
    test("tournamentUrlFromEnv reads PICHESS_TOURNAMENT_URL when set") {
      for
        _ <- TestSystem
          .putEnv(TournamentProxy.EnvTournamentUrl, "http://nc.test:8086")
        url <- TournamentProxy.tournamentUrlFromEnv
      yield assertTrue(url == "http://nc.test:8086")
    },
    test("tournamentUrlFromEnv falls back to the default when unset") {
      for url <- TournamentProxy.tournamentUrlFromEnv
      yield assertTrue(url == "http://141.37.123.132:8086")
    },
    test("botControlUrlFromEnv reads PICHESS_BOT_CONTROL_URL when set") {
      for
        _ <- TestSystem
          .putEnv(TournamentProxy.EnvBotControlUrl, "http://bot.test:8080")
        url <- TournamentProxy.botControlUrlFromEnv
      yield assertTrue(url == "http://bot.test:8080")
    },
    test("botControlUrlFromEnv treats a blank env value as unset") {
      for
        _ <- TestSystem.putEnv(TournamentProxy.EnvBotControlUrl, "   ")
        url <- TournamentProxy.botControlUrlFromEnv
      yield assertTrue(url == "http://bot-tournament:8080")
    },
    test("botNameFromEnv reads PICHESS_BOT_NAME when set") {
      for
        _    <- TestSystem.putEnv(TournamentProxy.EnvBotName, "piChess")
        name <- TournamentProxy.botNameFromEnv
      yield assertTrue(name == "piChess")
    },
    test("botNameFromEnv falls back to the default when unset") {
      for name <- TournamentProxy.botNameFromEnv
      yield assertTrue(name == "pichess")
    }
  )

package chess.controller

import zio.*
import zio.http.*
import zio.test.*

/** Guards the tournament-spectate follower's snapshot poll
  * ([[TournamentSpectate.fetchSnapshot]]): a slow/unreachable upstream must TIME
  * OUT (so the follow loop's existing retry kicks in) rather than hang and pin a
  * connection — the gateway-wide risk under a slow NowChess server with many
  * spectated games. This test IS the regression guard: drop the timeout and the
  * unhappy case hangs → the test-timeout aspect fails it.
  *
  * Mirrors the TournamentProxy `/tournament/list` relay timeout.
  */
object TournamentSpectateFetchSpec extends ZIOSpecDefault:

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

  private def at(s: String): URL = URL.decode(s).toOption.get

  def spec = suite("TournamentSpectate.fetchSnapshot")(
    test("happy: parses a normal snapshot well within the timeout") {
      val ok = Routes(
        Method.GET / "api" / "tournament" / "t" / "game" / "g" ->
          handler(Response.json("""{"moves":"e2e4 e7e5","status":"ongoing"}"""))
      )
      serveWith(ok) { base =>
        for
          presence <- SpectatorPresence.make
          sp       <- TournamentSpectate.make(presence)
          res <- sp.fetchSnapshot(at(s"$base/api/tournament/t/game/g")).either
        yield assertTrue(res.isRight)
      }
    },
    test("unhappy: an unresponsive upstream times out, it does not hang") {
      // The handler never completes, so the server never responds. With the
      // timeout, fetchSnapshot FAILS fast; without it, this test would hang and
      // be killed by the timeout aspect.
      val hang = Routes(
        Method.GET / "api" / "tournament" / "t" / "game" / "g" ->
          handler(ZIO.never.as(Response.json("{}")))
      )
      serveWith(hang) { base =>
        for
          presence <- SpectatorPresence.make
          sp       <- TournamentSpectate.make(presence)
          res <- sp
            .fetchSnapshot(at(s"$base/api/tournament/t/game/g"), 300.millis)
            .either
        yield assertTrue(res.isLeft)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(20.seconds)

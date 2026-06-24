package chess.bot.tournament

import zio.*
import zio.http.*
import zio.stream.*
import zio.test.*

import chess.bot.engine.{Evaluator, Search}
import chess.bot.tournament.TournamentApiClient.{
  GamePlayers,
  RegisterResult,
  TournamentInfo
}

/** Tests the control routes by running requests straight through the `Routes`
  * (no real server), against a real [[TournamentManager]] over a stub client
  * whose tournament stream never completes (so joined tournaments stay active).
  */
object TournamentControlApiSpec extends ZIOSpecDefault:

  private val realSearch: Search = Search.alphaBeta(Evaluator.materialOnly)
  private val info =
    TournamentInfo("t", "T", TournamentClock(limit = 60, increment = 0))
  private val me = BotRef("bot_x", "piChess")
  private val opp = BotRef("bot_o", "Opponent")

  /** Minimal stub; `failRegister` makes `manager.join` (hence the POST) fail.
    */
  private final class StubApi(failRegister: Boolean = false)
      extends TournamentApiClient:
    def register(name: String): IO[Throwable, RegisterResult] =
      if failRegister then ZIO.fail(new RuntimeException("register boom"))
      else ZIO.succeed(RegisterResult("bot_x", "tok"))
    def listTournaments: IO[Throwable, List[TournamentInfo]] = ZIO.succeed(Nil)
    def getTournament(id: String): IO[Throwable, TournamentInfo] =
      ZIO.succeed(info)
    def getGame(id: String, gameId: String): IO[Throwable, GamePlayers] =
      ZIO.succeed(GamePlayers(me, opp))
    def joinTournament(id: String): IO[Throwable, Unit] = ZIO.unit
    def streamTournament(id: String): ZStream[Any, Throwable, TournamentEvent] =
      ZStream.never
    def streamGame(
        id: String,
        gameId: String
    ): ZStream[Any, Throwable, GameEvent] = ZStream.empty
    def makeMove(id: String, gameId: String, uci: String): IO[Throwable, Unit] =
      ZIO.unit

  private def managerOver(stub: TournamentApiClient): UIO[TournamentManager] =
    TournamentManager.make(
      "piChess",
      2,
      () => realSearch,
      stub,
      reconnectDelay = Duration.Zero
    )

  def spec = suite("TournamentControlApi")(
    test("GET /health → ok") {
      for
        mgr <- managerOver(new StubApi())
        res <- TournamentControlApi
          .routes(mgr)
          .runZIO(Request.get(url"/health"))
        body <- res.body.asString
      yield assertTrue(res.status == Status.Ok, body == "ok")
    },
    test("GET /control/tournaments → empty active list initially") {
      for
        mgr <- managerOver(new StubApi())
        res <- TournamentControlApi
          .routes(mgr)
          .runZIO(Request.get(url"/control/tournaments"))
        body <- res.body.asString
      yield assertTrue(res.status == Status.Ok, body == """{"active":[]}""")
    },
    test("POST /control/tournaments/{id} joins and reports it active") {
      for
        mgr <- managerOver(new StubApi())
        res <- TournamentControlApi
          .routes(mgr)
          .runZIO(Request.post(url"/control/tournaments/t1", Body.empty))
        body <- res.body.asString
        active <- mgr.activeTournaments
        _ <- mgr.leave("t1") // cleanup the never-ending player fiber
      yield assertTrue(
        res.status == Status.Ok,
        body.contains("\"ok\":true"),
        active.contains("t1")
      )
    },
    test("GET /control/tournaments lists a joined tournament") {
      for
        mgr <- managerOver(new StubApi())
        _ <- mgr.join("t1")
        res <- TournamentControlApi
          .routes(mgr)
          .runZIO(Request.get(url"/control/tournaments"))
        body <- res.body.asString
        _ <- mgr.leave("t1")
      yield assertTrue(res.status == Status.Ok, body == """{"active":["t1"]}""")
    },
    test("POST join → 502 BadGateway when registration fails") {
      for
        mgr <- managerOver(new StubApi(failRegister = true))
        res <- TournamentControlApi
          .routes(mgr)
          .runZIO(Request.post(url"/control/tournaments/t1", Body.empty))
        body <- res.body.asString
      yield assertTrue(
        res.status == Status.BadGateway,
        body.contains("join t1 failed")
      )
    },
    test("DELETE /control/tournaments/{id} leaves") {
      for
        mgr <- managerOver(new StubApi())
        _ <- mgr.join("t1")
        res <- TournamentControlApi
          .routes(mgr)
          .runZIO(
            Request(method = Method.DELETE, url = url"/control/tournaments/t1")
          )
        active <- mgr.activeTournaments
      yield assertTrue(res.status == Status.NoContent, !active.contains("t1"))
    }
  )

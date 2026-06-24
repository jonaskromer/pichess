package chess.bot.tournament

import zio.*
import zio.stream.*
import zio.test.*

import chess.bot.engine.{Evaluator, Search}
import chess.bot.tournament.TournamentApiClient.{
  GamePlayers,
  RegisterResult,
  TournamentInfo
}
import chess.model.piece.Color

/** [[TournamentManager]] — register-once + play-many behaviour: idempotent
  * registration (incl. under concurrency), join/dedupe/leave, and the
  * supervised per-tournament reconnect+cleanup.
  */
object TournamentManagerSpec extends ZIOSpecDefault:

  private val realSearch: Search = Search.alphaBeta(Evaluator.materialOnly)
  private val info =
    TournamentInfo("t1", "Test", TournamentClock(limit = 60, increment = 0))
  private val me = BotRef("bot_x", "piChess")
  private val opp = BotRef("bot_opp", "Opponent")

  /** Stub recording calls; `tournamentStream` is what every `streamTournament`
    * subscription returns (tests pass `ZStream.never` to keep a tournament
    * "active", or a fail-once stream to exercise supervision).
    */
  private final class StubApi(
      val calls: Ref[List[String]],
      tournamentStream: ZStream[Any, Throwable, TournamentEvent]
  ) extends TournamentApiClient:
    def register(name: String): IO[Throwable, RegisterResult] =
      // yield first so concurrent callers genuinely interleave — proves the
      // register-once guard, not just luck.
      ZIO.yieldNow *> calls
        .update(s"register:$name" :: _)
        .as(RegisterResult("bot_x", "tok"))
    def listTournaments: IO[Throwable, List[TournamentInfo]] = ZIO.succeed(Nil)
    def getTournament(id: String): IO[Throwable, TournamentInfo] =
      calls.update(s"get:$id" :: _).as(info)
    def getGame(id: String, gameId: String): IO[Throwable, GamePlayers] =
      ZIO.succeed(GamePlayers(me, opp))
    def joinTournament(id: String): IO[Throwable, Unit] =
      calls.update(s"join:$id" :: _)
    def streamTournament(id: String): ZStream[Any, Throwable, TournamentEvent] =
      tournamentStream
    def streamGame(
        id: String,
        gameId: String
    ): ZStream[Any, Throwable, GameEvent] = ZStream.empty
    def makeMove(id: String, gameId: String, uci: String): IO[Throwable, Unit] =
      calls.update(s"move:$gameId:$uci" :: _)

  /** A manager over a stub whose tournament stream never completes (so a joined
    * tournament stays "active" for inspection).
    */
  private def managerNever: UIO[(TournamentManager, StubApi)] =
    for
      calls <- Ref.make(List.empty[String])
      stub = new StubApi(calls, ZStream.never)
      mgr <- TournamentManager.make(
        "piChess",
        2,
        () => realSearch,
        stub,
        reconnectDelay = Duration.Zero
      )
    yield (mgr, stub)

  def spec = suite("TournamentManager")(
    suite("ensureRegistered")(
      test("registers once and returns the same id") {
        for
          (mgr, stub) <- managerNever
          id1 <- mgr.ensureRegistered
          id2 <- mgr.ensureRegistered
          recorded <- stub.calls.get
        yield assertTrue(
          id1 == "bot_x",
          id2 == "bot_x",
          recorded.count(_ == "register:piChess") == 1
        )
      },
      test("registers at most once under concurrency") {
        for
          (mgr, stub) <- managerNever
          _ <- ZIO.foreachParDiscard(1 to 20)(_ => mgr.ensureRegistered)
          recorded <- stub.calls.get
        yield assertTrue(recorded.count(_ == "register:piChess") == 1)
      },
      test("make uses the default reconnect delay when none is given") {
        for
          calls <- Ref.make(List.empty[String])
          mgr <- TournamentManager.make(
            "piChess",
            2,
            () => realSearch,
            new StubApi(calls, ZStream.never)
          )
          active <- mgr.activeTournaments
        yield assertTrue(active.isEmpty)
      }
    ),
    suite("join / leave / active")(
      test("join starts a tournament (it becomes active)") {
        for
          (mgr, _) <- managerNever
          _ <- mgr.join("t1")
          active <- mgr.activeTournaments
          _ <- mgr.leave("t1") // cleanup the never-ending player fiber
        yield assertTrue(active == Set("t1"))
      },
      test("join is idempotent — a repeat join is a no-op (registers once)") {
        for
          (mgr, stub) <- managerNever
          _ <- mgr.join("t1")
          _ <- mgr.join("t1")
          active <- mgr.activeTournaments
          recorded <- stub.calls.get
          _ <- mgr.leave("t1")
        yield assertTrue(
          active == Set("t1"),
          recorded.count(_ == "register:piChess") == 1
        )
      },
      test("plays multiple tournaments concurrently") {
        for
          (mgr, _) <- managerNever
          _ <- mgr.join("t1")
          _ <- mgr.join("t2")
          active <- mgr.activeTournaments
          _ <- mgr.leave("t1") *> mgr.leave("t2")
        yield assertTrue(active == Set("t1", "t2"))
      },
      test("leave stops an active tournament") {
        for
          (mgr, _) <- managerNever
          _ <- mgr.join("t1")
          _ <- mgr.leave("t1")
          active <- mgr.activeTournaments
        yield assertTrue(active.isEmpty)
      },
      test("leave is a no-op for an unknown tournament") {
        for
          (mgr, _) <- managerNever
          _ <- mgr.leave("nope")
          active <- mgr.activeTournaments
        yield assertTrue(active.isEmpty)
      }
    ),
    suite("supervised")(
      test("retries on a stream failure, then completes and cleans up") {
        for
          calls <- Ref.make(List.empty[String])
          attempts <- Ref.make(0)
          // first subscription fails; second completes (empty) → one retry.
          stream = ZStream.fromZIO(attempts.getAndUpdate(_ + 1)).flatMap { n =>
            if n == 0 then
              ZStream
                .fail(new RuntimeException("simulated tournament-stream drop"))
            else ZStream.empty
          }
          stub = new StubApi(calls, stream)
          mgr <- TournamentManager.make(
            "piChess",
            2,
            () => realSearch,
            stub,
            reconnectDelay = Duration.Zero
          )
          _ <- mgr.supervised("t1", "bot_x")
          n <- attempts.get
          recorded <- stub.calls.get
        yield assertTrue(
          n == 2, // failed once, retried, then completed
          recorded.count(_ == "get:t1") == 2 // playTournament ran twice
        )
      }
    )
  )

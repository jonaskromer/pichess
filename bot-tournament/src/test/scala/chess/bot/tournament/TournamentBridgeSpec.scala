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
import chess.repository.api.ArchiveSubmissionDto

/** End-to-end [[TournamentBridge]] behaviour against an in-memory
  * [[TournamentApiClient]] stub that records calls and serves canned streams.
  *
  * The key tournament-specific subtlety under test: `gameStart` is broadcast
  * (both colours, every game) to every subscriber, so the bridge must
  * self-filter by our registered id and dedupe by gameId.
  */
object TournamentBridgeSpec extends ZIOSpecDefault:

  private val myId = "bot_x"
  private val realSearch: Search = Search.alphaBeta(Evaluator.materialOnly)
  private val info =
    TournamentInfo(
      "t1",
      "Test Tournament",
      TournamentClock(limit = 60, increment = 0)
    )

  private val whiteToMove =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val blackToMove =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
  // Small clocks (seconds) keep the budgeted search to ~tens of ms in tests.
  private val clock = GameClock(whiteTime = 2.0, blackTime = 2.0)

  private val me = BotRef(myId, "piChess")
  private val opp = BotRef("bot_opp", "Opponent")

  /** Recording stub. Captures calls into [[calls]]; serves the pre-loaded
    * tournament / per-game streams and a `gamePlayers` lookup table (a gameId
    * absent from it makes `getGame` fail). `failJoin` / `failMove` exercise the
    * Bridge's catchAll branches.
    */
  private final class StubApi(
      tournamentEvents: ZStream[Any, Throwable, TournamentEvent],
      gameEvents: Map[String, ZStream[Any, Throwable, GameEvent]],
      gamePlayers: Map[String, GamePlayers],
      val calls: Ref[List[String]],
      failJoin: Boolean = false,
      failMove: Boolean = false
  ) extends TournamentApiClient:
    def register(name: String): IO[Throwable, RegisterResult] =
      calls.update(s"register:$name" :: _).as(RegisterResult(myId, "tok"))
    def listTournaments: IO[Throwable, List[TournamentInfo]] =
      ZIO.succeed(List(info))
    def getTournament(id: String): IO[Throwable, TournamentInfo] =
      calls.update(s"get:$id" :: _).as(info)
    def getGame(id: String, gameId: String): IO[Throwable, GamePlayers] =
      calls.update(s"getGame:$gameId" :: _) *>
        ZIO
          .fromOption(gamePlayers.get(gameId))
          .orElseFail(new RuntimeException(s"no such game: $gameId"))
    def joinTournament(id: String): IO[Throwable, Unit] =
      if failJoin then ZIO.fail(new RuntimeException(s"sim join failure: $id"))
      else calls.update(s"join:$id" :: _)
    def streamTournament(id: String): ZStream[Any, Throwable, TournamentEvent] =
      tournamentEvents
    def streamGame(
        id: String,
        gameId: String
    ): ZStream[Any, Throwable, GameEvent] =
      gameEvents.getOrElse(gameId, ZStream.empty)
    def makeMove(id: String, gameId: String, uci: String): IO[Throwable, Unit] =
      if failMove then
        ZIO.fail(new RuntimeException(s"sim move failure: $gameId/$uci"))
      else calls.update(s"move:$gameId:$uci" :: _)

  private def newStub(
      tournament: List[TournamentEvent] = Nil,
      games: Map[String, List[GameEvent]] = Map.empty,
      gamePlayers: Map[String, GamePlayers] = Map.empty,
      failJoin: Boolean = false,
      failMove: Boolean = false
  ): UIO[StubApi] =
    Ref.make(List.empty[String]).map { calls =>
      new StubApi(
        ZStream.fromIterable(tournament),
        games.view.mapValues(es => ZStream.fromIterable(es)).toMap,
        gamePlayers,
        calls,
        failJoin,
        failMove
      )
    }

  private def runGameWith(
      api: TournamentApiClient,
      color: Color,
      search: () => Search = () => realSearch
  ) =
    TournamentBridge.runGame(
      "t1",
      "g1",
      color,
      opponent = "Opponent",
      incMs = 0L,
      fallbackDepth = 2,
      search,
      api
    )

  def spec = suite("TournamentBridge")(
    suite("ourSide — self-filter by registered id")(
      test("we are white → (White, opponent)") {
        assertTrue(
          TournamentBridge.ourSide(GamePlayers(me, opp), myId) == Some(
            (Color.White, opp)
          )
        )
      },
      test("we are black → (Black, opponent)") {
        assertTrue(
          TournamentBridge.ourSide(GamePlayers(opp, me), myId) == Some(
            (Color.Black, opp)
          )
        )
      },
      test("not our game") {
        assertTrue(
          TournamentBridge.ourSide(GamePlayers(opp, opp), myId) == None
        )
      }
    ),
    suite("resolveOurColor — dedupe + lookup")(
      test("our game (white) → Some((White, opp)) and the gameId is claimed") {
        for
          stub <- newStub(gamePlayers = Map("g1" -> GamePlayers(me, opp)))
          started <- Ref.make(Set.empty[String])
          res <- TournamentBridge.resolveOurColor(
            "t1",
            "g1",
            myId,
            started,
            stub
          )
          claimed <- started.get
        yield assertTrue(
          res == Some((Color.White, opp)),
          claimed.contains("g1")
        )
      },
      test("our game (black) → Some((Black, opp))") {
        for
          stub <- newStub(gamePlayers = Map("g1" -> GamePlayers(opp, me)))
          started <- Ref.make(Set.empty[String])
          res <- TournamentBridge
            .resolveOurColor("t1", "g1", myId, started, stub)
        yield assertTrue(res == Some((Color.Black, opp)))
      },
      test(
        "a game we're not in → None but stays claimed (so the dup is skipped)"
      ) {
        for
          stub <- newStub(gamePlayers = Map("g1" -> GamePlayers(opp, opp)))
          started <- Ref.make(Set.empty[String])
          res <- TournamentBridge
            .resolveOurColor("t1", "g1", myId, started, stub)
          claimed <- started.get
        yield assertTrue(res == None, claimed.contains("g1"))
      },
      test("an already-claimed gameId → None without a lookup (dedupe)") {
        for
          stub <- newStub(gamePlayers = Map("g1" -> GamePlayers(me, opp)))
          started <- Ref.make(Set("g1"))
          res <- TournamentBridge.resolveOurColor(
            "t1",
            "g1",
            myId,
            started,
            stub
          )
          recorded <- stub.calls.get
        yield assertTrue(
          res == None,
          !recorded.exists(_.startsWith("getGame:"))
        )
      },
      test("a failed lookup → None and un-claims so a re-announce can retry") {
        for
          stub <- newStub(gamePlayers = Map.empty) // g1 absent → getGame fails
          started <- Ref.make(Set.empty[String])
          res <- TournamentBridge
            .resolveOurColor("t1", "g1", myId, started, stub)
          claimed <- started.get
        yield assertTrue(res == None, !claimed.contains("g1"))
      }
    ),
    suite("runGame — per-game orchestration")(
      test("posts a move when it's our turn (we're white, startpos snapshot)") {
        val ev = GameEvent
          .StateSnapshot(whiteToMove, "", Color.White, clock, "ongoing", None)
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.White)
          recorded <- stub.calls.get
        yield assertTrue(recorded.count(_.startsWith("move:g1:")) == 1)
      },
      test("does not move when it's the opponent's turn") {
        val ev = GameEvent
          .StateSnapshot(whiteToMove, "", Color.White, clock, "ongoing", None)
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.Black)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("waits (no move) on a pending game") {
        val ev = GameEvent
          .StateSnapshot(whiteToMove, "", Color.White, clock, "pending", None)
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.White)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("posts a move after the opponent moves (move event, our turn)") {
        val ev = GameEvent.MovePlayed("e2e4", blackToMove, Color.Black, clock)
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.Black)
          recorded <- stub.calls.get
        yield assertTrue(recorded.count(_.startsWith("move:g1:")) == 1)
      },
      test("does nothing on a finished game (gameEnd)") {
        val ev = GameEvent.GameEnded(Some(Color.White), "checkmate")
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.Black)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("logs and continues on a malformed FEN (no move)") {
        val ev = GameEvent
          .StateSnapshot("not a fen", "", Color.White, clock, "ongoing", None)
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.White)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("ignores a heartbeat (keep-alive, no move)") {
        for
          stub <- newStub(games = Map("g1" -> List(GameEvent.Heartbeat)))
          _ <- runGameWith(stub, Color.White)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("a terminal snapshot finishes the game (records outcome, no move)") {
        // Terminal `gameState` with an opening move log → emitFinished classifies
        // result/opening/length from the snapshot; no move is posted.
        val ev = GameEvent.StateSnapshot(
          whiteToMove,
          "e2e4 e7e5",
          Color.White,
          clock,
          "checkmate",
          Some(Color.Black)
        )
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.White)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("records the finished game to the recorder sink") {
        // A move is played, then the game ends with White winning. The recorder
        // sink should receive a submission with that move + the result + names.
        val events = List(
          GameEvent.MovePlayed("e2e4", blackToMove, Color.Black, clock),
          GameEvent.GameEnded(Some(Color.White), "checkmate")
        )
        for
          captured <- Ref.make(Option.empty[ArchiveSubmissionDto])
          recorder  = GameRecorder("piChess", dto => captured.set(Some(dto)))
          stub     <- newStub(games = Map("g1" -> events))
          _ <- TournamentBridge.runGame(
            "t1",
            "g1",
            Color.White,
            "Rival",
            0L,
            2,
            () => realSearch,
            stub,
            recorder = Some(recorder)
          )
          sub <- captured.get
        yield assertTrue(
          sub.exists(_.moves.map(_.uci) == List("e2e4")),
          sub.map(_.result) == Some("1-0"),
          sub.map(_.white) == Some("piChess"),
          sub.map(_.black) == Some("Rival"),
          sub.map(_.source) == Some("tournament")
        )
      },
      test("a terminal snapshot then gameEnd emits the outcome only once") {
        // Both terminal events can arrive (snapshot-then-end on a reconnect);
        // the `finished` guard makes the second a no-op.
        val events = List(
          GameEvent
            .StateSnapshot(whiteToMove, "", Color.White, clock, "draw", None),
          GameEvent.GameEnded(None, "draw")
        )
        for
          stub <- newStub(games = Map("g1" -> events))
          _ <- runGameWith(stub, Color.White)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      }
    ),
    suite("runGame — failure paths")(
      test("a failed makeMove is caught; the fiber stays alive") {
        val ev = GameEvent
          .StateSnapshot(whiteToMove, "", Color.White, clock, "ongoing", None)
        for
          stub <- newStub(games = Map("g1" -> List(ev)), failMove = true)
          _ <- runGameWith(stub, Color.White)
        yield assertCompletes
      },
      test("reconnects after a stream drop and resumes play") {
        // First subscription fails (a dropped NDJSON connection); the retry
        // re-subscribes and the second attempt serves a real snapshot, on which
        // we move. Proves the per-game stream is reconnected, not abandoned.
        for
          attempts <- Ref.make(0)
          moved <- Ref.make(false)
          api = new TournamentApiClient:
            def register(name: String) = ZIO.succeed(RegisterResult("b", "t"))
            def listTournaments = ZIO.succeed(Nil)
            def getTournament(id: String) = ZIO.succeed(info)
            def getGame(id: String, gameId: String) =
              ZIO.succeed(GamePlayers(me, opp))
            def joinTournament(id: String) = ZIO.unit
            def streamTournament(id: String) = ZStream.empty
            def streamGame(
                id: String,
                gameId: String
            ): ZStream[Any, Throwable, GameEvent] =
              ZStream.fromZIO(attempts.getAndUpdate(_ + 1)).flatMap { n =>
                if n == 0 then
                  ZStream.fail(new RuntimeException("simulated stream drop"))
                else
                  ZStream.fromIterable(
                    List(
                      GameEvent.StateSnapshot(
                        whiteToMove,
                        "",
                        Color.White,
                        clock,
                        "ongoing",
                        None
                      )
                    )
                  )
              }
            def makeMove(id: String, gameId: String, uci: String) =
              moved.set(true)
          _ <- TournamentBridge.runGame(
            "t1",
            "g1",
            Color.White,
            "Opponent",
            0L,
            2,
            () => realSearch,
            api,
            reconnectDelay = Duration.Zero
          )
          a <- attempts.get
          m <- moved.get
        yield assertTrue(a == 2, m)
      },
      test(
        "a non-retryable defect stops the fiber gracefully (caught + logged)"
      ) {
        val dying = new TournamentApiClient:
          def register(name: String) = ZIO.succeed(RegisterResult("b", "t"))
          def listTournaments = ZIO.succeed(Nil)
          def getTournament(id: String) = ZIO.succeed(info)
          def getGame(id: String, gameId: String) =
            ZIO.succeed(GamePlayers(me, opp))
          def joinTournament(id: String) = ZIO.unit
          def streamTournament(id: String) = ZStream.empty
          def streamGame(
              id: String,
              gameId: String
          ): ZStream[Any, Throwable, GameEvent] =
            ZStream.fromZIO(ZIO.die(new RuntimeException("boom")))
          def makeMove(id: String, gameId: String, uci: String) = ZIO.unit
        for _ <- TournamentBridge.runGame(
            "t1",
            "g1",
            Color.White,
            "Opponent",
            0L,
            2,
            () => realSearch,
            dying,
            reconnectDelay = Duration.Zero
          )
        yield assertCompletes
      },
      test("a no-move search never resigns and posts nothing") {
        val noMove = new Search:
          def bestMove(
              s: chess.model.board.GameState,
              depth: Int,
              history: Set[Long]
          ) =
            ZIO.succeed(None)
        val ev = GameEvent.StateSnapshot(
          whiteToMove,
          "",
          Color.White,
          clock,
          "ongoing",
          None
        )
        for
          stub <- newStub(games = Map("g1" -> List(ev)))
          _ <- runGameWith(stub, Color.White, () => noMove)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      }
    ),
    suite("playTournament — one tournament")(
      test("joins, reads the clock, then streams; dedupes gameStart") {
        // g1 is announced twice (White then Black) — getGame must fire once.
        val events = List(
          TournamentEvent.TournamentStarted,
          TournamentEvent.Heartbeat, // keep-alive; must be silently ignored
          TournamentEvent.RoundStarted(1),
          TournamentEvent.GameStart(1, "g1", Color.White),
          TournamentEvent.GameStart(1, "g1", Color.Black),
          TournamentEvent.RoundFinished(1),
          TournamentEvent.TournamentFinished(BotRef("bot_x", "piChess"))
        )
        for
          stub <- newStub(
            tournament = events,
            games = Map("g1" -> Nil),
            gamePlayers = Map("g1" -> GamePlayers(me, opp))
          )
          _ <- TournamentBridge.playTournament(
            "t1",
            myId,
            2,
            () => realSearch,
            stub
          )
          recorded <- stub.calls.get
        yield assertTrue(
          recorded.contains("join:t1"),
          recorded.contains("get:t1"),
          recorded.count(_ == "getGame:g1") == 1
        )
      },
      test("tolerates a join failure (e.g. director already added the bot)") {
        val events =
          List(TournamentEvent.TournamentFinished(BotRef("bot_x", "piChess")))
        for
          stub <- newStub(tournament = events, failJoin = true)
          _ <- TournamentBridge.playTournament(
            "t1",
            myId,
            2,
            () => realSearch,
            stub
          )
          recorded <- stub.calls.get
        yield assertTrue(
          recorded.contains("get:t1"),
          !recorded.exists(_.startsWith("join:"))
        )
      }
    )
  )

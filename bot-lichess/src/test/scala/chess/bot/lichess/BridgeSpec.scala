package chess.bot.lichess

import zio.*
import zio.stream.*
import zio.test.*

import chess.bot.engine.{Evaluator, Search}

/** End-to-end Bridge behaviour against an in-memory [[BotApiClient]]
  * stub. The stub records the calls Bridge makes (acceptChallenge,
  * makeMove, resign) and serves canned event streams so we can drive
  * Bridge through realistic event sequences without a real Lichess
  * connection.
  */
object BridgeSpec extends ZIOSpecDefault:

  private val botName = "piChess"
  private val realSearch: Search = Search.alphaBeta(Evaluator.materialOnly)

  /** Recording stub. Captures every call into [[calls]]; serves the
    * pre-loaded [[accountEvents]] / [[gameEvents]] streams. */
  private final class StubApi(
      val accountEvents: ZStream[Any, Throwable, AccountEvent],
      val gameEvents: Map[String, ZStream[Any, Throwable, GameEvent]],
      val calls: Ref[List[String]],
  ) extends BotApiClient:
    def streamEvents: ZStream[Any, Throwable, AccountEvent] = accountEvents
    def streamGame(gameId: String): ZStream[Any, Throwable, GameEvent] =
      gameEvents.getOrElse(gameId, ZStream.empty)
    def acceptChallenge(id: String): IO[Throwable, Unit] =
      calls.update(s"accept:$id" :: _)
    def makeMove(gameId: String, uci: String): IO[Throwable, Unit] =
      calls.update(s"move:$gameId:$uci" :: _)
    def resign(gameId: String): IO[Throwable, Unit] =
      calls.update(s"resign:$gameId" :: _)

  private def newStub(
      events: List[AccountEvent] = Nil,
      games: Map[String, List[GameEvent]] = Map.empty,
  ): UIO[StubApi] =
    Ref.make(List.empty[String]).map { calls =>
      new StubApi(
        accountEvents = ZStream.fromIterable(events),
        gameEvents = games.view.mapValues(es => ZStream.fromIterable(es)).toMap,
        calls = calls,
      )
    }

  /** Stub that fails every mutating call — exercises the catchAll
    * branches in Bridge.handleAction. */
  private final class FailingApi(
      val events: List[AccountEvent],
      val games: Map[String, List[GameEvent]],
  ) extends BotApiClient:
    def streamEvents: ZStream[Any, Throwable, AccountEvent] =
      ZStream.fromIterable(events)
    def streamGame(id: String): ZStream[Any, Throwable, GameEvent] =
      games.get(id).fold(ZStream.empty)(ZStream.fromIterable(_))
    def acceptChallenge(id: String): IO[Throwable, Unit] =
      ZIO.fail(new RuntimeException(s"sim accept failure: $id"))
    def makeMove(gameId: String, uci: String): IO[Throwable, Unit] =
      ZIO.fail(new RuntimeException(s"sim move failure: $gameId/$uci"))
    def resign(gameId: String): IO[Throwable, Unit] =
      ZIO.fail(new RuntimeException(s"sim resign failure: $gameId"))

  private val botPlayer   = PlayerRef(Some("bot1"), Some("piChess"))
  private val humanPlayer = PlayerRef(Some("juu"),  Some("juu"))

  private val standardChallenge = ChallengeInfo(
    id = "ch1", rated = true,
    variant = VariantRef("standard"),
    speed = "blitz", timeControl = TimeControlRef("clock"),
    challenger = humanPlayer,
  )
  private val chess960Challenge = standardChallenge.copy(
    id = "ch2",
    variant = VariantRef("chess960"),
  )
  private val casualChallenge = standardChallenge.copy(
    id = "ch3",
    rated = false,
  )

  def spec = suite("Bridge")(
    suite("acceptance policy")(
      test("shouldAccept returns true for a standard rated challenge") {
        assertTrue(Bridge.shouldAccept(standardChallenge))
      },
      test("shouldAccept returns false for chess960") {
        assertTrue(!Bridge.shouldAccept(chess960Challenge))
      },
      test("shouldAccept returns false for a casual challenge (would burn the daily cap for no rating)") {
        assertTrue(!Bridge.shouldAccept(casualChallenge))
      },
    ),
    suite("account event dispatch")(
      test("accepts a standard challenge via the API client") {
        for
          stub <- newStub(events = List(AccountEvent.Challenge(standardChallenge)))
          _    <- Bridge.run(botName, () => realSearch, searchDepth = 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(recorded.contains("accept:ch1"))
      },
      test("does not accept a chess960 challenge") {
        for
          stub <- newStub(events = List(AccountEvent.Challenge(chess960Challenge)))
          _    <- Bridge.run(botName, () => realSearch, searchDepth = 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("ignores challengeCanceled / challengeDeclined") {
        for
          stub <- newStub(events = List(
                    AccountEvent.ChallengeCanceled(standardChallenge),
                    AccountEvent.ChallengeDeclined(standardChallenge),
                  ))
          _ <- Bridge.run(botName, () => realSearch, searchDepth = 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("forks a per-game fiber on GameStart") {
        // GameStart on the account stream calls runGame via forkDaemon.
        // The daemon fiber's lifecycle isn't observable from Bridge.run
        // directly, so we verify by: an empty per-game stream + the
        // account stream completes without error.
        val gameStart = AccountEvent.GameStart(GameRef("g-fork"))
        for
          stub <- newStub(events = List(gameStart), games = Map("g-fork" -> Nil))
          _    <- Bridge.run(botName, () => realSearch, searchDepth = 2, stub)
        yield assertCompletes
      },
    ),
    suite("per-game orchestration via runGame")(
      test("posts a move when it's our turn (initial position, we're white)") {
        // gameFull at startpos → white to move → we are white → MoveFrom
        val gameFull = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = botPlayer, black = humanPlayer,
          state = GameStateUpdate("", 300, 300, 0, 0, "started"),
        )
        for
          stub <- newStub(games = Map("g1" -> List(gameFull)))
          _    <- Bridge.runGame("g1", botName, () => realSearch, 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(
          recorded.exists(_.startsWith("move:g1:")),
          recorded.size == 1,
        )
      },
      test("does not post a move when it's opponent's turn") {
        // gameFull at startpos → white to move → we are black → wait
        val gameFull = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = humanPlayer, black = botPlayer,
          state = GameStateUpdate("", 300, 300, 0, 0, "started"),
        )
        for
          stub <- newStub(games = Map("g1" -> List(gameFull)))
          _    <- Bridge.runGame("g1", botName, () => realSearch, 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("posts a move after opponent plays") {
        val gameFull = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = humanPlayer, black = botPlayer,
          state = GameStateUpdate("", 300, 300, 0, 0, "started"),
        )
        val opponentMoved = GameEvent.GameStateEvent(
          moves = "e2e4",
          wtime = 300, btime = 300, winc = 0, binc = 0,
          status = "started",
        )
        for
          stub <- newStub(games = Map("g1" -> List(gameFull, opponentMoved)))
          _    <- Bridge.runGame("g1", botName, () => realSearch, 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(
          recorded.exists(_.startsWith("move:g1:")),
          recorded.size == 1,    // one move only, in response to the GameState delta
        )
      },
      test("does nothing on a finished game's events") {
        val gameFull = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = botPlayer, black = humanPlayer,
          state = GameStateUpdate("", 300, 300, 0, 0, "mate"),
        )
        for
          stub <- newStub(games = Map("g1" -> List(gameFull)))
          _    <- Bridge.runGame("g1", botName, () => realSearch, 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
      test("logs and continues when an event is malformed (GameState before GameFull)") {
        // An orphan GameStateEvent (no preceding GameFull) triggers
        // Action.MalformedEvent; Bridge logs and continues — no move
        // is posted, no resign happens.
        val orphan = GameEvent.GameStateEvent(
          moves  = "", wtime = 300, btime = 300, winc = 0, binc = 0,
          status = "started",
        )
        for
          stub <- newStub(games = Map("g1" -> List(orphan)))
          _    <- Bridge.runGame("g1", botName, () => realSearch, 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(recorded.isEmpty)
      },
    ),
    suite("failure-path coverage")(
      test("makeMove failure is caught and logged, fiber stays alive") {
        // Bridge.handleAction's catchAll on the MoveFrom branch:
        // a failed POST shouldn't crash the per-game fiber, just log.
        val gameFull = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = botPlayer, black = humanPlayer,
          state = GameStateUpdate("", 300, 300, 0, 0, "started"),
        )
        val api = new FailingApi(Nil, Map("g1" -> List(gameFull)))
        for _ <- Bridge.runGame("g1", botName, () => realSearch, 2, api)
        yield assertCompletes
      },
      test("acceptChallenge failure is caught") {
        val api = new FailingApi(
          List(AccountEvent.Challenge(standardChallenge)),
          Map.empty,
        )
        for _ <- Bridge.run(botName, () => realSearch, 2, api)
        yield assertCompletes
      },
      test("game-stream failure is caught by catchAllCause") {
        // streamGame fails mid-stream → Bridge.runGame's catchAllCause
        // logs and returns cleanly instead of propagating.
        val failingApi = new BotApiClient:
          def streamEvents: ZStream[Any, Throwable, AccountEvent] = ZStream.empty
          def streamGame(gid: String): ZStream[Any, Throwable, GameEvent] =
            ZStream.fail(new RuntimeException("simulated stream drop"))
          def acceptChallenge(id: String): IO[Throwable, Unit] = ZIO.unit
          def makeMove(g: String, u: String): IO[Throwable, Unit] = ZIO.unit
          def resign(g: String): IO[Throwable, Unit] = ZIO.unit
        for _ <- Bridge.runGame("g-fail", botName, () => realSearch, 2, failingApi)
        yield assertCompletes
      },
      test("no-move search does not resign (plays on instead of surrendering)") {
        // Regression: the Bridge used to resign the instant the search
        // returned None, which made the bot concede every game it didn't
        // outright win. It must now make NO resign call — just log and
        // await the next event — so the opponent has to prove the win.
        val gameFull = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = botPlayer, black = humanPlayer,
          state = GameStateUpdate("", 300, 300, 0, 0, "started"),
        )
        val noMoveSearch = new Search:
          def bestMove(
              state: chess.model.board.GameState,
              depth: Int,
              history: Set[Long],
          ): UIO[Option[chess.model.board.Move]] =
            ZIO.succeed(None)
        for
          stub     <- newStub(games = Map("g1" -> List(gameFull)))
          _        <- Bridge.runGame("g1", botName, () => noMoveSearch, 2, stub)
          recorded <- stub.calls.get
        yield assertTrue(!recorded.exists(_.startsWith("resign:")))
      },
    ),
  )

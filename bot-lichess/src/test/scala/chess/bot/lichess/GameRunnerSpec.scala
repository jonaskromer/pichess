package chess.bot.lichess

import zio.test.*

import chess.model.piece.Color

/** Pins [[GameRunner.decide]]'s output for representative event
  * sequences. Everything that follows in the bridge ultimately runs
  * off these decisions, so changes here ripple through to gameplay.
  */
object GameRunnerSpec extends ZIOSpecDefault:

  // Realistic player records — the bot's username is matched against
  // `name` case-insensitively.
  private val botPlayer    = PlayerRef(Some("bot1"), Some("piChess"))
  private val humanPlayer  = PlayerRef(Some("juu"),  Some("juu"))

  private val initialState = GameStateUpdate(
    moves  = "",
    wtime  = 60000, btime = 60000, winc = 0, binc = 0,
    status = "started",
  )

  /** Build a GameFull where pichess plays white. */
  private def gameFullAsWhite(moves: String = "") = GameEvent.GameFull(
    id         = "game1",
    initialFen = "startpos",
    white      = botPlayer,
    black      = humanPlayer,
    state      = initialState.copy(moves = moves),
  )

  /** Build a GameFull where pichess plays black. */
  private def gameFullAsBlack(moves: String = "") = GameEvent.GameFull(
    id         = "game1",
    initialFen = "startpos",
    white      = humanPlayer,
    black      = botPlayer,
    state      = initialState.copy(moves = moves),
  )

  def spec = suite("GameRunner.decide")(
    suite("GameFull")(
      test("white pichess at startpos → MoveFrom (our turn)") {
        val (next, action) = GameRunner.decide(gameFullAsWhite(), "piChess", None)
        assertTrue(
          next.exists(_.ourColor == Color.White),
          action match
            case GameRunner.Action.MoveFrom(s) => s.activeColor == Color.White
            case _ => false,
        )
      },
      test("black pichess at startpos → None (white moves first)") {
        val (next, action) = GameRunner.decide(gameFullAsBlack(), "piChess", None)
        assertTrue(
          next.exists(_.ourColor == Color.Black),
          action == GameRunner.Action.None,
        )
      },
      test("black pichess after white's e2e4 → MoveFrom (our turn)") {
        val (next, action) =
          GameRunner.decide(gameFullAsBlack(moves = "e2e4"), "piChess", None)
        assertTrue(
          next.exists(_.ourColor == Color.Black),
          action match
            case GameRunner.Action.MoveFrom(s) => s.activeColor == Color.Black
            case _ => false,
        )
      },
      test("status=mate at startpos resolves to GameOver") {
        // A finished game on the very first envelope (e.g. our bot
        // reconnects after the game already ended). Status drives the
        // exit; the moves payload doesn't have to be a real mate
        // sequence — only the status string is consulted here.
        val mateFull = gameFullAsWhite().copy(
          state = initialState.copy(status = "mate")
        )
        val (_, action) = GameRunner.decide(mateFull, "piChess", None)
        assertTrue(action == GameRunner.Action.GameOver)
      },
      test("bot name not in either slot → MalformedEvent") {
        val (next, action) =
          GameRunner.decide(gameFullAsWhite(), "someoneElse", None)
        assertTrue(
          next.isEmpty,
          action match
            case GameRunner.Action.MalformedEvent(_) => true
            case _ => false,
        )
      },
      test("name match is case-insensitive") {
        // Lichess accepts any-casing; our matcher normalises so
        // "PICHESS" and "pichess" both find the bot.
        val (_, action) = GameRunner.decide(gameFullAsWhite(), "PICHESS", None)
        assertTrue(
          action match
            case GameRunner.Action.MoveFrom(_) => true
            case _ => false,
        )
      },
    ),
    suite("GameStateEvent")(
      test("ours-to-move state update → MoveFrom") {
        val full = gameFullAsWhite()
        val (gameFullState, _) = GameRunner.decide(full, "piChess", None)
        val opponentMoved = GameEvent.GameStateEvent(
          moves  = "e2e4 e7e5",  // we played e2e4, opponent replied e7e5 → our turn
          wtime = 60000, btime = 60000, winc = 0, binc = 0,
          status = "started",
        )
        val (_, action) = GameRunner.decide(opponentMoved, "piChess", gameFullState)
        assertTrue(
          action match
            case GameRunner.Action.MoveFrom(s) => s.activeColor == Color.White
            case _ => false,
        )
      },
      test("ours-just-moved state update → None (opponent's turn)") {
        val full = gameFullAsWhite()
        val (gameFullState, _) = GameRunner.decide(full, "piChess", None)
        val justPlayed = GameEvent.GameStateEvent(
          moves  = "e2e4",
          wtime = 60000, btime = 60000, winc = 0, binc = 0,
          status = "started",
        )
        val (_, action) = GameRunner.decide(justPlayed, "piChess", gameFullState)
        assertTrue(action == GameRunner.Action.None)
      },
      test("status=resign → GameOver") {
        val full = gameFullAsWhite()
        val (gameFullState, _) = GameRunner.decide(full, "piChess", None)
        val resigned = GameEvent.GameStateEvent(
          moves  = "e2e4",
          wtime = 60000, btime = 60000, winc = 0, binc = 0,
          status = "resign",
        )
        val (_, action) = GameRunner.decide(resigned, "piChess", gameFullState)
        assertTrue(action == GameRunner.Action.GameOver)
      },
      test("GameStateEvent before GameFull → MalformedEvent") {
        val orphan = GameEvent.GameStateEvent(
          moves = "", wtime = 60000, btime = 60000, winc = 0, binc = 0,
          status = "started",
        )
        val (_, action) = GameRunner.decide(orphan, "piChess", None)
        assertTrue(
          action match
            case GameRunner.Action.MalformedEvent(_) => true
            case _ => false,
        )
      },
      test("illegal UCI in moves → MalformedEvent") {
        // e2e4 is fine, but e4e5 is a pawn pushing TWO squares from a
        // non-starting rank — illegal in the rules engine. Should
        // surface as MalformedEvent rather than crash.
        val full = gameFullAsWhite()
        val (gameFullState, _) = GameRunner.decide(full, "piChess", None)
        val bogus = GameEvent.GameStateEvent(
          moves  = "e2e4 e7e5 e4e7",  // last move is illegal
          wtime = 60000, btime = 60000, winc = 0, binc = 0,
          status = "started",
        )
        val (_, action) = GameRunner.decide(bogus, "piChess", gameFullState)
        assertTrue(
          action match
            case GameRunner.Action.MalformedEvent(_) => true
            case _ => false,
        )
      },
    ),
    suite("non-game events")(
      test("ChatLine produces Action.None and preserves state") {
        val full = gameFullAsWhite()
        val (gameFullState, _) = GameRunner.decide(full, "piChess", None)
        val chat = GameEvent.ChatLine(room = "player", username = "juu", text = "gl")
        val (after, action) = GameRunner.decide(chat, "piChess", gameFullState)
        assertTrue(action == GameRunner.Action.None, after == gameFullState)
      },
      test("OpponentGone produces Action.None and preserves state") {
        val full = gameFullAsWhite()
        val (gameFullState, _) = GameRunner.decide(full, "piChess", None)
        val gone = GameEvent.OpponentGone(gone = true, claimWinInSeconds = Some(30))
        val (after, action) = GameRunner.decide(gone, "piChess", gameFullState)
        assertTrue(action == GameRunner.Action.None, after == gameFullState)
      },
    ),
    suite("initialFen handling")(
      test("a real FEN parses and routes correctly") {
        // Custom starting position: black to move, pichess plays black.
        val customFen = "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 2"
        val full = GameEvent.GameFull(
          id = "g2",
          initialFen = customFen,
          white = humanPlayer,
          black = botPlayer,
          state = initialState,
        )
        val (next, action) = GameRunner.decide(full, "piChess", None)
        assertTrue(
          next.exists(_.ourColor == Color.Black),
          action match
            case GameRunner.Action.MoveFrom(s) => s.activeColor == Color.Black
            case _ => false,
        )
      },
      test("malformed initialFen surfaces as MalformedEvent") {
        val full = gameFullAsWhite().copy(initialFen = "not a fen at all")
        val (next, action) = GameRunner.decide(full, "piChess", None)
        assertTrue(
          next.isDefined,    // we still record our color
          action match
            case GameRunner.Action.MalformedEvent(_) => true
            case _ => false,
        )
      },
    ),
  )

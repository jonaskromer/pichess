package chess.bot.lichess

import chess.bot.lichess.internal.SyncCodec
import chess.model.board.GameState
import chess.model.piece.Color

/** Pure per-game decision logic.
  *
  * Each Lichess [[GameEvent]] is handed to [[decide]] alongside the
  * bot's username and the running state (color, initial position).
  * The result is a tuple of the next running state and an [[Action]]
  * the orchestrator should perform. The split keeps the I/O-free
  * choice logic unit-testable end-to-end without needing a real
  * Lichess connection or a stubbed sttp backend.
  *
  * The orchestrator wires the Action by:
  *   - [[Action.None]]            → no-op (typical for opponent moves)
  *   - [[Action.MoveFrom]]        → run search, POST the chosen UCI
  *   - [[Action.GameOver]]        → tear down the per-game fiber
  *   - [[Action.MalformedEvent]]  → log + resign / abandon
  */
object GameRunner:

  /** Stable per-game context discovered from the initial [[GameEvent.GameFull]]
    * envelope. Carried forward so subsequent [[GameEvent.GameStateEvent]]
    * deltas can reconstruct the current position via "initial FEN +
    * cumulative move list" without re-parsing the GameFull payload. */
  final case class State(ourColor: Color, initialFen: String)

  enum Action:
    case None                                                       // nothing to do
    case MoveFrom(state: GameState, ourTimeMs: Long, ourIncMs: Long) // our turn — search + POST
    case GameOver                                                   // status != "started"/"created"
    case MalformedEvent(reason: String)                             // bad event payload

  /** Drive one event. Returns the next [[State]] (may differ on
    * GameFull where we discover our color) and an [[Action]] that the
    * orchestrator interprets. */
  def decide(
      event: GameEvent,
      botUsername: String,
      previous: Option[State],
  ): (Option[State], Action) =
    event match
      case full: GameEvent.GameFull =>
        decideGameFull(full, botUsername)
      case state: GameEvent.GameStateEvent =>
        previous match
          case Some(s) => decideGameState(state, s)
          case None    =>
            (previous, Action.MalformedEvent("gameState before gameFull"))
      case _: GameEvent.ChatLine | _: GameEvent.OpponentGone =>
        (previous, Action.None)

  /** Process the initial gameFull envelope: discover our color, parse
    * the initial FEN, derive the current state from initialFen +
    * state.moves, and check whether it's our turn. */
  private def decideGameFull(
      event: GameEvent.GameFull,
      botUsername: String,
  ): (Option[State], Action) =
    detectColor(event, botUsername) match
      case Left(err) =>
        (None, Action.MalformedEvent(err))
      case Right(ourColor) =>
        val runState = State(ourColor, event.initialFen)
        deriveState(event.initialFen, event.state.moves) match
          case Left(err) =>
            (Some(runState), Action.MalformedEvent(err))
          case Right(_) if !isPlaying(event.state.status) =>
            (Some(runState), Action.GameOver)
          case Right(current) =>
            val s = event.state
            (Some(runState), turnAction(current, ourColor, s.wtime, s.btime, s.winc, s.binc))

  /** Process a per-move state update — same shape as the GameFull
    * substate, but on a stream that pre-supposes the GameFull arrived. */
  private def decideGameState(
      event: GameEvent.GameStateEvent,
      run: State,
  ): (Option[State], Action) =
    deriveState(run.initialFen, event.moves) match
      case Left(err) =>
        (Some(run), Action.MalformedEvent(err))
      case Right(_) if !isPlaying(event.status) =>
        (Some(run), Action.GameOver)
      case Right(current) =>
        (Some(run), turnAction(current, run.ourColor, event.wtime, event.btime, event.winc, event.binc))

  /** Recompute the live `GameState` from initialFen + the cumulative
    * UCI move list Lichess includes on every state event. The
    * re-derivation is sub-millisecond at typical game depths, and
    * keeps us stateless across reconnects (Lichess re-sends the same
    * cumulative moves string on resume). */
  private def deriveState(initialFen: String, movesUci: String): Either[String, GameState] =
    SyncCodec.parseFen(initialFen).flatMap { initial =>
      val tokens =
        if movesUci.isEmpty then Array.empty[String]
        else movesUci.split(' ')
      tokens.foldLeft[Either[String, GameState]](Right(initial)) { (acc, uci) =>
        acc.flatMap { state =>
          UciCodec.parse(uci).flatMap { move =>
            SyncCodec.applyMove(state, move)
              .toRight(s"Illegal move replayed: $uci")
          }
        }
      }
    }

  /** Determine which colour the bot is playing by matching the
    * `botUsername` against the white/black PlayerRef names. Returns
    * Left when the bot isn't listed at all — that's a malformed event
    * since Lichess only streams events for games the bot is in. */
  private def detectColor(
      full: GameEvent.GameFull,
      botUsername: String,
  ): Either[String, Color] =
    val whiteMatch = full.white.name.exists(_.equalsIgnoreCase(botUsername))
    val blackMatch = full.black.name.exists(_.equalsIgnoreCase(botUsername))
    if whiteMatch then Right(Color.White)
    else if blackMatch then Right(Color.Black)
    else Left(s"Bot '$botUsername' not in game ${full.id}")

  /** Map the current GameState's active colour against our colour: if
    * they line up it's our turn → [[Action.MoveFrom]]; otherwise we
    * wait → [[Action.None]]. */
  private def turnAction(
      current: GameState,
      ourColor: Color,
      wtime: Long,
      btime: Long,
      winc: Long,
      binc: Long,
  ): Action =
    if current.activeColor == ourColor then
      val (ourTime, ourInc) =
        if ourColor == Color.White then (wtime, winc) else (btime, binc)
      Action.MoveFrom(current, ourTime, ourInc)
    else Action.None

  /** Lichess' status string is "created" before first move or
    * "started" while in play; anything else (mate / draw / resign /
    * stalemate / timeout / aborted) means the game ended. */
  private def isPlaying(status: String): Boolean =
    status == "started" || status == "created"

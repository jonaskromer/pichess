package chess.bot.tournament

import chess.bot.tournament.internal.SyncCodec
import chess.model.board.GameState
import chess.model.piece.Color

/** Pure per-game decision logic for the NowChess tournament bridge.
  *
  * Each [[GameEvent]] is handed to [[decide]] alongside the colour we play
  * (discovered once from [[TournamentEvent.GameStart.color]]). The result is an
  * [[Action]] the orchestrator performs. Keeping the choice logic I/O-free
  * makes it fully unit-testable without a live server.
  *
  * Simpler than the Lichess `GameRunner`: every NowChess event carries the
  * authoritative post-move `fen` and `turn`, so there's no cumulative-move
  * replay and no need to carry running state between events.
  */
object TournamentRunner:

  enum Action:
    case None // not our turn / waiting
    // our turn — search + POST. Clocks are SECONDS as the server sends them;
    // the orchestrator converts to ms and adds the increment for TimeManager.
    case MoveFrom(state: GameState, ourTimeSec: Double, oppTimeSec: Double)
    case GameOver // game finished
    case MalformedEvent(reason: String) // unparseable FEN

  /** Decide what to do for one game event.
    *
    *   - `gameState` snapshot: `ongoing` → act on the turn; `pending` (queued
    *     behind `maxConcurrentGames`) → wait; anything else (terminal) → over.
    *   - `move`: act on the (post-move) turn — termination arrives separately
    *     via `gameEnd`.
    *   - `gameEnd`: the game is over.
    */
  def decide(event: GameEvent, ourColor: Color): Action =
    event match
      case GameEvent.StateSnapshot(fen, _, turn, clock, status, _) =>
        status match
          case "ongoing" => turnAction(fen, ourColor, turn, clock)
          case "pending" => Action.None
          case _         => Action.GameOver
      case GameEvent.MovePlayed(_, fen, turn, clock) =>
        turnAction(fen, ourColor, turn, clock)
      case _: GameEvent.GameEnded =>
        Action.GameOver

  /** If it's our turn, parse the FEN and emit a move request with our and the
    * opponent's remaining clocks; otherwise wait. A malformed FEN is surfaced
    * rather than crashing the game fiber.
    */
  private def turnAction(
      fen: String,
      ourColor: Color,
      turn: Color,
      clock: GameClock
  ): Action =
    if turn != ourColor then Action.None
    else
      SyncCodec.parseFen(fen) match
        case Left(err) => Action.MalformedEvent(err)
        case Right(state) =>
          val (ourTime, oppTime) =
            if ourColor == Color.White then (clock.whiteTime, clock.blackTime)
            else (clock.blackTime, clock.whiteTime)
          Action.MoveFrom(state, ourTime, oppTime)

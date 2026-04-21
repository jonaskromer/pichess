package chess.model.board

import chess.model.piece.Color

case class GameState(
    board: Board,
    activeColor: Color,
    enPassantTarget: Option[Position] = None,
    inCheck: Boolean = false,
    castlingRights: CastlingRights = CastlingRights(),
    status: GameStatus = GameStatus.Playing,
    halfmoveClock: Int = 0,
    fullmoveNumber: Int = 1
):
  /** Transition to a terminal status. No-op if the game is already over —
    * terminal states are sticky by invariant. Use this helper at every
    * transition site so future terminal cases (Resignation, Timeout, …)
    * automatically inherit the sticky-terminal property.
    */
  def endWith(terminal: GameStatus): GameState =
    if status.isPlaying then copy(status = terminal) else this

object GameState:
  val initial: GameState = GameState(Board.initial, Color.White)

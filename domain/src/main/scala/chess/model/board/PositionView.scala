package chess.model.board

import chess.model.piece.Color

/** Read-only view of a full position: the board plus the metadata that
  * evaluation, move generation, and Zobrist hashing read. Implemented by
  * the immutable [[GameState]] (public) and by the search-internal
  * mutable position used for copy-make.
  *
  * Deliberately exposes only what the internal hot readers need (board,
  * side to move, en-passant target, castling rights, halfmove clock) —
  * the public `GameState` carries more (`status`, `fullmoveNumber`,
  * `inCheck`) which those readers don't consult. */
trait PositionView:
  def board: BoardLike
  def activeColor: Color
  def enPassantTarget: Option[Position]
  def castlingRights: CastlingRights
  def halfmoveClock: Int

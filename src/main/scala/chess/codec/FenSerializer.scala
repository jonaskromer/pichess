package chess.codec

import chess.model.board.GameState

/** Serializes a [[GameState]] to a FEN (Forsyth–Edwards Notation) string.
  *
  * This is the counterpart of [[FenParser]] and together they form the
  * round-trip contract:
  * {{{
  *   parser.parse(FenSerializer.serialize(state)) == Right(state)
  * }}}
  *
  * All field-level encoding delegates to [[FenCodec]], which co-locates encode
  * and decode for each field — the same pattern [[JsonCodec]] uses for JSON.
  */
object FenSerializer:

  def serialize(state: GameState): String =
    s"${positionKey(state)} ${state.halfmoveClock} ${state.fullmoveNumber}"

  /** First four FEN fields (placement, active color, castling, en passant).
    * Used for position comparison in threefold/fivefold repetition detection.
    */
  def positionKey(state: GameState): String =
    val placement = FenCodec.encodeBoard(state.board)
    val active = FenCodec.encodeColor(state.activeColor)
    val castling = FenCodec.encodeCastling(state.castlingRights)
    val enPassant = FenCodec.encodeEnPassant(state.enPassantTarget)
    s"$placement $active $castling $enPassant"

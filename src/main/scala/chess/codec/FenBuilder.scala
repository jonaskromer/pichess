package chess.codec

import chess.model.board.GameState
import chess.model.rules.MoveValidator

/** Converts tokenized FEN fields into a [[GameState]].
  *
  * Each of the three [[FenParser]] implementations tokenizes a FEN string
  * differently (parser-combinator grammar, fastparse grammar, regex), but they
  * all produce the same six raw field strings. The conversion from those raw
  * fields to a validated domain object delegates to [[FenCodec]] for each
  * field's decode, so all parsers behave identically from the caller's
  * perspective.
  *
  * `inCheck` is computed from the board via [[MoveValidator.isInCheck]] so that
  * imported positions display the check highlight correctly. FEN does not
  * encode game status, so `status` is always [[GameStatus.Playing]] — game-end
  * detection happens on the next move attempt.
  */
private[codec] object FenBuilder:

  def build(
      placement: String,
      activeColor: String,
      castling: String,
      enPassant: String,
      halfmove: String,
      fullmove: String
  ): Either[String, GameState] =
    for
      board <- FenCodec.decodeBoard(placement)
      color <- FenCodec.decodeColor(activeColor)
      rights <- FenCodec.decodeCastling(castling)
      epTarget <- FenCodec.decodeEnPassant(enPassant)
      hm <- FenCodec.decodeNonNegativeInt(halfmove, "halfmove clock")
      fm <- FenCodec.decodePositiveInt(fullmove, "fullmove number")
    yield GameState(
      board = board,
      activeColor = color,
      enPassantTarget = epTarget,
      inCheck = MoveValidator.isInCheck(board, color),
      castlingRights = rights,
      halfmoveClock = hm,
      fullmoveNumber = fm
    )

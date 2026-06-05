package chess.codec

import chess.model.board.{Move, Position}
import chess.model.piece.PieceType

/** UCI ↔ [[Move]] translation.
  *
  * Lichess, Stockfish, and every other engine wire format use UCI
  * notation:
  *   - 4 chars  → "e2e4" — plain move
  *   - 5 chars  → "e7e8q" — promotion (q/r/b/n)
  *
  * Castling is encoded as the king's two-square move ("e1g1"); en
  * passant is the diagonal pawn move with no extra marker — the
  * server side reconstructs the implicit pawn capture from the
  * GameState's en-passant target. Both fall out for free when we
  * round-trip through [[Move]].
  *
  * Lives in the `codec` module rather than any specific protocol
  * adapter (bot-lichess for Lichess, bot-train for Stockfish
  * tournament play) because UCI is a generic wire format consumed
  * by multiple subsystems.
  */
object UciCodec:

  /** Parse a UCI string into a [[Move]]. Returns `Left(error)` for any
    * malformed input (wrong length, out-of-range square, unrecognised
    * promotion letter) — these are upstream contract violations, not
    * runtime failures the bot should surface as game state. */
  def parse(uci: String): Either[String, Move] =
    if uci.length != 4 && uci.length != 5 then
      Left(s"Invalid UCI length: '$uci' (expected 4 or 5 chars)")
    else
      for
        from <- parseSquare(uci.substring(0, 2))
        to   <- parseSquare(uci.substring(2, 4))
        promo <-
          if uci.length == 5 then parsePromotion(uci.charAt(4)).map(Some(_))
          else Right(None)
      yield Move(from, to, promo)

  /** Render a [[Move]] as a UCI string. The inverse of [[parse]]. */
  def serialize(move: Move): String =
    val base = s"${move.from.col}${move.from.row}${move.to.col}${move.to.row}"
    move.promotion match
      case Some(PieceType.Queen)  => base + "q"
      case Some(PieceType.Rook)   => base + "r"
      case Some(PieceType.Bishop) => base + "b"
      case Some(PieceType.Knight) => base + "n"
      case Some(_)                => base   // King/Pawn promotion makes no sense; ignore
      case None                   => base

  private def parseSquare(s: String): Either[String, Position] =
    val col = s.charAt(0)
    val rowChar = s.charAt(1)
    if col < 'a' || col > 'h' then Left(s"Invalid file: '$col'")
    else if rowChar < '1' || rowChar > '8' then Left(s"Invalid rank: '$rowChar'")
    else Right(Position(col, rowChar - '0'))

  private def parsePromotion(c: Char): Either[String, PieceType] = c match
    case 'q' => Right(PieceType.Queen)
    case 'r' => Right(PieceType.Rook)
    case 'b' => Right(PieceType.Bishop)
    case 'n' => Right(PieceType.Knight)
    case other => Left(s"Invalid promotion piece: '$other'")

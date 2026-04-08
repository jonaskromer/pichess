package chess.codec

import chess.model.board.GameState

/** FEN parser built on `scala.util.matching.Regex` with no external parser
  * library.
  *
  * Demonstrates the "no library" baseline: the six fields of a FEN string are
  * matched by a single anchored regular expression and the captured groups are
  * handed off to the shared [[FenBuilder]] for semantic validation. This is the
  * most compact of the three implementations because FEN's grammar is regular.
  */
object FenParserRegex extends FenParser:

  private val fenPattern =
    """^([pnbrqkPNBRQK1-8/]+) ([wb]) (-|[KQkq]+) (-|[a-h][1-8]) (\d+) (\d+)$""".r

  override def parse(input: String): Either[String, GameState] =
    input match
      case fenPattern(
            placement,
            active,
            castling,
            enPassant,
            halfmove,
            fullmove
          ) =>
        FenBuilder.build(
          placement,
          active,
          castling,
          enPassant,
          halfmove,
          fullmove
        )
      case _ =>
        Left(s"Input does not match FEN structure: '$input'")

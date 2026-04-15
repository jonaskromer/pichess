package chess.codec

import chess.model.GameError
import chess.model.board.GameState
import zio.*

/** Parses a FEN (Forsyth–Edwards Notation) string into a [[GameState]].
  *
  * Three implementations are provided, each demonstrating a different parsing
  * technique:
  *
  *   - [[FenParserCombinator]] — `scala-parser-combinators` (`RegexParsers`)
  *   - [[FenParserFastParse]] — `fastparse` (macro-based combinators)
  *   - [[FenParserRegex]] — `scala.util.matching.Regex`, no external library
  *
  * All three tokenize the FEN string into six raw field strings and then
  * delegate semantic validation to the shared [[FenBuilder]].
  *
  * @see
  *   [[FenSerializer]] for the encode direction.
  */
trait FenParser:
  def parse(input: String): IO[GameError, GameState]

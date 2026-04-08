package chess.codec

import chess.model.board.GameState

/** Parses a FEN (Forsyth–Edwards Notation) string into a [[GameState]].
  *
  * FEN is the standard text format for encoding a chess position. It is used
  * here as the import format for persistence and for the REST API that will be
  * introduced in phase 4 — import/export, game saving, and load-from-URL all
  * flow through this interface.
  *
  * Three implementations are provided, each demonstrating a different parsing
  * technique:
  *
  *   - [[FenParserCombinator]] — built on `scala-parser-combinators` /
  *     `RegexParsers`
  *   - [[FenParserFastParse]] — built on the `fastparse` library
  *   - [[FenParserRegex]] — built on `scala.util.matching.Regex` with no
  *     external parser library
  *
  * All three agree on the semantic result for any given input. Implementations
  * return `Left(msg)` for invalid input; the error message is intended for
  * logging and REST responses, not for programmatic matching.
  */
trait FenParser:
  def parse(input: String): Either[String, GameState]

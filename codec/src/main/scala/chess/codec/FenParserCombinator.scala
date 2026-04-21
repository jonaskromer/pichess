package chess.codec

import chess.model.GameError
import chess.model.board.GameState
import zio.*

import scala.util.parsing.combinator.RegexParsers

/** FEN parser built on `scala-parser-combinators`.
  *
  * The grammar tokenizes a FEN string into six raw field strings via
  * [[RegexParsers]] combinators, then defers the semantic conversion to
  * [[FenBuilder]] so that all three parser implementations share the same
  * validation rules and error messages for domain-level problems (e.g. "rank
  * doesn't sum to 8", "invalid piece character").
  *
  * The parser uses `parseAll` so any unconsumed trailing input is an error.
  */
object FenParserCombinator extends FenParser with RegexParsers:

  override def skipWhitespace: Boolean = false

  private def placement: Parser[String] = """[pnbrqkPNBRQK1-8/]+""".r

  private def activeColor: Parser[String] = """[wb]""".r

  private def castling: Parser[String] = """(-|[KQkq]+)""".r

  private def enPassant: Parser[String] = """(-|[a-h][1-8])""".r

  private def integer: Parser[String] = """\d+""".r

  private def space: Parser[String] = """ """.r

  private def fen: Parser[(String, String, String, String, String, String)] =
    placement ~ space ~ activeColor ~ space ~ castling ~
      space ~ enPassant ~ space ~ integer ~ space ~ integer ^^ {
        case pp ~ _ ~ ac ~ _ ~ cr ~ _ ~ ep ~ _ ~ hm ~ _ ~ fm =>
          (pp, ac, cr, ep, hm, fm)
      }

  override def parse(input: String): IO[GameError, GameState] =
    parseAll(fen, input) match
      case Success((pp, ac, cr, ep, hm, fm), _) =>
        FenBuilder.build(pp, ac, cr, ep, hm, fm)
      case ns: NoSuccess =>
        ZIO.fail(GameError.ParseError(s"[${ns.next.pos}] ${ns.msg}"))

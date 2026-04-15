package chess.codec

import chess.model.GameError
import chess.model.board.GameState
import zio.*

import fastparse.*
import fastparse.NoWhitespace.*

/** FEN parser built on the `fastparse` library.
  *
  * Demonstrates fastparse's macro-based combinator style. The grammar is
  * defined as a set of `[$: P]` methods that compose via `~`, `.rep`, and `.!`
  * (capture). Tokenization produces six raw field strings, which are then
  * converted to a [[GameState]] by the shared [[FenBuilder]].
  *
  * The parser uses `End` to ensure no trailing input remains.
  */
object FenParserFastParse extends FenParser:

  private def placement[$: P]: P[String] =
    P(CharIn("pnbrqkPNBRQK1-8/").rep(1).!)

  private def activeColor[$: P]: P[String] =
    P(CharIn("wb").!)

  private def castling[$: P]: P[String] =
    P(("-".! | CharIn("KQkq").rep(1).!))

  private def enPassant[$: P]: P[String] =
    P(("-".! | (CharIn("a-h") ~ CharIn("1-8")).!))

  private def integer[$: P]: P[String] =
    P(CharIn("0-9").rep(1).!)

  private def fen[$: P]: P[(String, String, String, String, String, String)] =
    P(
      placement ~ " " ~ activeColor ~ " " ~ castling ~ " " ~
        enPassant ~ " " ~ integer ~ " " ~ integer ~ End
    )

  override def parse(input: String): IO[GameError, GameState] =
    fastparse.parse(input, fen(using _)) match
      case Parsed.Success((pp, ac, cr, ep, hm, fm), _) =>
        FenBuilder.build(pp, ac, cr, ep, hm, fm)
      case f: Parsed.Failure =>
        ZIO.fail(
          GameError.ParseError(
            s"Failed to parse FEN at index ${f.index}: expected ${f.label}"
          )
        )

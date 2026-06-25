package chess.codec

import chess.model.piece.Color

/** One move plus its optional PGN annotations — the input to
  * [[PgnSerializer.serializeAnnotated]].
  *
  *   - `nag` is a Numeric Annotation Glyph code (emitted as `$n`, e.g. `$3` for
  *     a brilliant move — see [[Nag]]);
  *   - `clockMs` is the side's remaining clock AFTER the move (→ `[%clk]`);
  *   - `emtMs` is the elapsed move time (→ `[%emt]`).
  */
final case class PgnMove(
    color: Color,
    san: String,
    nag: Option[Int] = None,
    clockMs: Option[Long] = None,
    emtMs: Option[Long] = None
):
  def annotated: Boolean = nag.isDefined || clockMs.isDefined || emtMs.isDefined

/** The annotations [[PgnParser]] preserves for one move, aligned 1:1 with the
  * parsed move history. `empty` when the move carried no comment/NAG.
  */
final case class MoveAnnotation(
    nag: Option[Int] = None,
    clockMs: Option[Long] = None,
    emtMs: Option[Long] = None
):
  def isEmpty: Boolean = nag.isEmpty && clockMs.isEmpty && emtMs.isEmpty

object MoveAnnotation:
  val empty: MoveAnnotation = MoveAnnotation()

/** Standard PGN move-assessment NAGs (`$1`–`$6`) ↔ their symbol glyphs. Used to
  * emit/parse `$n` tokens and to convert glued glyphs (`Nf3!`) on import.
  */
object Nag:
  val Good: Int        = 1 // !
  val Mistake: Int     = 2 // ?
  val Brilliant: Int   = 3 // !!
  val Blunder: Int     = 4 // ??
  val Interesting: Int = 5 // !?
  val Dubious: Int     = 6 // ?!

  private val toSymbol: Map[Int, String] =
    Map(1 -> "!", 2 -> "?", 3 -> "!!", 4 -> "??", 5 -> "!?", 6 -> "?!")
  private val fromSymbol: Map[String, Int] = toSymbol.map((c, s) => s -> c)

  /** Glyph string for a NAG code (`3` → `"!!"`), or None if not a `$1`–`$6`. */
  def symbol(code: Int): Option[String] = toSymbol.get(code)

  /** NAG code for a glyph string (`"!!"` → `3`), or None if unrecognised. */
  def code(symbol: String): Option[Int] = fromSymbol.get(symbol)

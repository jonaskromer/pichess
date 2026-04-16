package chess.model.board

import chess.model.GameError
import zio.{IO, ZIO}

/** A square on the chess board, identified by a file (column) `a`–`h` and
  * a rank (row) `1`–`8`.
  *
  * The primary constructor is `private[chess]` so external callers cannot
  * construct a Position directly. They must go through [[Position.make]],
  * which validates the coordinates and returns a ZIO failure on
  * out-of-range input.
  *
  * Internal (`chess.*`) code continues to use the synthetic `apply` directly
  * because construction sites there are bounds-checked by their callers —
  * e.g. [[chess.model.rules.Ray.walk]] verifies `col`/`row` before
  * constructing, and moves derived from already-valid Positions remain
  * valid by arithmetic. Treating these internal sites as trusted avoids
  * effect-propagation overhead through the legal-move generator.
  *
  * The case class machinery (structural equality, hashCode, pattern-match
  * `unapply`) remains public, so `Position`-keyed maps, equality
  * comparisons, and destructuring all work unchanged.
  */
case class Position private[chess] (col: Char, row: Int):
  override def toString: String = s"$col$row"

object Position:
  /** Validated factory for untrusted coordinates. Returns a ZIO failure
    * (as [[GameError.ParseError]]) when `col` is outside `a`–`h` or `row`
    * is outside `1`–`8`.
    */
  def make(col: Char, row: Int): IO[GameError, Position] =
    if col >= 'a' && col <= 'h' && row >= 1 && row <= 8 then
      ZIO.succeed(new Position(col, row))
    else
      ZIO.fail(GameError.ParseError(s"Invalid position: $col$row"))

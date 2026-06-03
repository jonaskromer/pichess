package chess.model.board

import chess.model.GameError
import zio.{IO, ZIO}

/** A square on the chess board, identified by a file (column) `a`–`h` and a
  * rank (row) `1`–`8`.
  *
  * The primary constructor is `private[chess]` so external callers cannot
  * construct a Position directly. They must go through [[Position.make]], which
  * validates the coordinates and returns a ZIO failure on out-of-range input.
  *
  * Internal (`chess.*`) code continues to use the synthetic `apply` directly
  * because construction sites there are bounds-checked by their callers — e.g.
  * [[chess.model.rules.Ray.walk]] verifies `col`/`row` before constructing, and
  * moves derived from already-valid Positions remain valid by arithmetic.
  * Treating these internal sites as trusted avoids effect-propagation overhead
  * through the legal-move generator.
  *
  * The case class machinery (structural equality, hashCode, pattern-match
  * `unapply`) remains public, so `Position`-keyed maps, equality comparisons,
  * and destructuring all work unchanged.
  */
case class Position private[chess] (col: Char, row: Int):
  override def toString: String = s"$col$row"

object Position:
  /** Pre-allocated flyweight table of all 64 valid positions, indexed by
    * `(col - 'a') * 8 + (row - 1)`.
    *
    * The hot-path move generator and FEN encoder allocate fresh Position
    * instances per ray step / per board cell (~1.5 KB per RayWalk call,
    * ~3 KB per MoveValidator.isInCheck call before this change). Returning
    * cached singletons from this table eliminates that allocation, which
    * also collapses object-equality to identity (`==` short-circuits on
    * `eq`) for valid coordinates.
    *
    * Constructed once at class load; never mutated.
    */
  private val cached: Array[Position] =
    val arr = new Array[Position](64)
    var c   = 0
    while c < 8 do
      var r = 0
      while r < 8 do
        arr(c * 8 + r) = new Position(('a' + c).toChar, r + 1)
        r += 1
      c += 1
    arr

  /** Internal flyweight lookup. Replaces the synthetic case-class `apply`
    * so all `Position('e', 4)` call sites pick up the cached instance
    * without allocating. Trusted-caller contract: internal `chess.*`
    * code passes coordinates already in range; out-of-range arguments
    * throw `ArrayIndexOutOfBoundsException` rather than silently
    * allocating an invalid position. Untrusted callers must use
    * [[Position.make]] which validates first.
    */
  def apply(col: Char, row: Int): Position =
    cached((col - 'a') * 8 + (row - 1))

  /** Validated factory for untrusted coordinates. Returns a ZIO failure (as
    * [[GameError.ParseError]]) when `col` is outside `a`–`h` or `row` is
    * outside `1`–`8`.
    */
  def make(col: Char, row: Int): IO[GameError, Position] =
    if col >= 'a' && col <= 'h' && row >= 1 && row <= 8 then
      ZIO.succeed(apply(col, row))
    else ZIO.fail(GameError.ParseError(s"Invalid position: $col$row"))

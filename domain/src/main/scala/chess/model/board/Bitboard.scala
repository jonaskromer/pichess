package chess.model.board

/** Phantom-typed wrapper over `Long`. Zero runtime cost in Scala 3 — the
  * JIT erases the wrapper at the call site — but the type discipline
  * prevents accidentally mixing a `Bitboard` with an arbitrary `Long`
  * (e.g. passing a piece-type index where a bitboard is expected).
  *
  * Convention: little-endian rank-file (LERF). Bit `i` corresponds to
  * square `i`, where `i = (row - 1) * 8 + (col - 'a')`. That puts
  * `a1` at bit 0, `h1` at bit 7, `a8` at bit 56, `h8` at bit 63 — the
  * same layout used by mainstream chess engines and the bitboard
  * literature.
  */
opaque type Bitboard = Long

object Bitboard:

  /** No squares set. */
  val Empty: Bitboard = 0L

  /** Single-bit bitboard with bit `idx` set (0 ≤ idx < 64). */
  inline def ofSquare(idx: Int): Bitboard = 1L << idx

  /** A bitboard from a raw `Long` payload. Used by tests and codecs
    * that need to construct specific bit patterns. */
  inline def fromLong(raw: Long): Bitboard = raw

  extension (bb: Bitboard)
    /** Escape hatch back to `Long` when interop is required. */
    inline def raw: Long = bb

    // Bit-level set ops -----------------------------------------------------
    inline def |(other: Bitboard): Bitboard = bb | other
    inline def &(other: Bitboard): Bitboard = bb & other
    inline def ^(other: Bitboard): Bitboard = bb ^ other
    inline def unary_~ : Bitboard           = ~bb

    // Predicates ------------------------------------------------------------
    inline def isEmpty: Boolean            = bb == 0L
    inline def nonEmpty: Boolean           = bb != 0L
    inline def containsIdx(idx: Int): Boolean =
      (bb & (1L << idx)) != 0L

    // Single-bit mutations (returns a NEW bitboard — opaque type Long is
    // a value, not a reference).
    inline def withBit(idx: Int): Bitboard    = bb | (1L << idx)
    inline def withoutBit(idx: Int): Bitboard = bb & ~(1L << idx)

    // Population helpers ----------------------------------------------------
    inline def popCount: Int        = java.lang.Long.bitCount(bb)
    inline def lowestBitIdx: Int    = java.lang.Long.numberOfTrailingZeros(bb)
    inline def clearLowestBit: Bitboard = bb & (bb - 1L)

    /** Iterate set bits low-to-high. Allocates one `Iterator`; consumers
      * that care about hot-loop allocation should fold via `while`-loops
      * on the raw `Long`.
      */
    def iterator: Iterator[Int] = new Iterator[Int]:
      private var rem: Long = bb
      def hasNext: Boolean = rem != 0L
      def next(): Int =
        val idx = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        idx

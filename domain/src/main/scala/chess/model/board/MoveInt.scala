package chess.model.board

import chess.model.piece.PieceType

/** Packed integer representation of a [[Move]] for the engine's
  * hot path.
  *
  * Layout (32-bit Int):
  * {{{
  *   bits  0–5   from square (LERF index 0–63)
  *   bits  6–11  to   square (LERF index 0–63)
  *   bits  12–14 promotion piece type (0 = none, 1 = Q, 2 = R, 3 = B, 4 = N)
  *   bits  15–31 reserved (future flags: capture / castle / EP / check)
  * }}}
  *
  * Why an Int? The search visits thousands of nodes per
  * `bestMove` call. Carrying [[Move]] case classes (with two
  * cached-but-still-pointer-traversed [[Position]] references and
  * an `Option[PieceType]` allocation for promotion) bloats the
  * move-list path. Packing into a plain Int collapses every move
  * to 4 bytes flat — no pointer chasing on equality, no
  * allocation in tight loops, no `Option`. The `Move` case class
  * stays for codecs / wire format / edge use; the engine
  * converts at the search boundary only.
  *
  * Encoding is stable: bits 0–14 are reserved for the move's
  * essential geometry, the upper bits for future enrichment
  * without invalidating existing snapshots (e.g. tagged check
  * moves for ordering).
  */
object MoveInt:

  // ── encoding ─────────────────────────────────────────────────────

  /** Sentinel for "no promotion". */
  inline val NoPromotion = 0
  inline val PromoQueen  = 1
  inline val PromoRook   = 2
  inline val PromoBishop = 3
  inline val PromoKnight = 4

  /** Pack a move into an Int. `fromIdx` / `toIdx` are LERF
    * square indices (0–63), `promo` one of the `Promo*`
    * constants. Inlined so the call site reduces to a couple of
    * shifts and an OR — no function-call overhead. */
  inline def encode(fromIdx: Int, toIdx: Int, promo: Int): Int =
    fromIdx | (toIdx << 6) | (promo << 12)

  /** Pack from a [[Move]] case class. Used at the rules ↔ engine
    * boundary; not on the hot path. */
  def encodeMove(move: Move): Int =
    encode(move.from.squareIdx, move.to.squareIdx, encodePromotion(move.promotion))

  inline def fromIdx(m: Int): Int = m & 0x3F
  inline def toIdx(m: Int):   Int = (m >>> 6) & 0x3F
  inline def promo(m: Int):   Int = (m >>> 12) & 0x7

  /** Decode back into a [[Move]] case class. Allocates — call
    * only at the search boundary (final returned best move). */
  def decode(m: Int): Move =
    Move(
      Position(('a' + (fromIdx(m) % 8)).toChar, fromIdx(m) / 8 + 1),
      Position(('a' + (toIdx(m)   % 8)).toChar, toIdx(m)   / 8 + 1),
      promo(m) match
        case NoPromotion => None
        case PromoQueen  => Some(PieceType.Queen)
        case PromoRook   => Some(PieceType.Rook)
        case PromoBishop => Some(PieceType.Bishop)
        case PromoKnight => Some(PieceType.Knight)
        case _           => None,
    )

  /** Promotion encoding from the optional [[PieceType]] used by
    * [[Move.promotion]]. Any non-promotable piece (king / pawn,
    * which shouldn't appear) collapses to `NoPromotion`. */
  def encodePromotion(p: Option[PieceType]): Int = p match
    case None                    => NoPromotion
    case Some(PieceType.Queen)   => PromoQueen
    case Some(PieceType.Rook)    => PromoRook
    case Some(PieceType.Bishop)  => PromoBishop
    case Some(PieceType.Knight)  => PromoKnight
    case _                       => NoPromotion

  /** Decode a promotion constant back to the optional piece type
    * the [[Move]] case class expects. */
  def decodePromotion(p: Int): Option[PieceType] = p match
    case PromoQueen  => Some(PieceType.Queen)
    case PromoRook   => Some(PieceType.Rook)
    case PromoBishop => Some(PieceType.Bishop)
    case PromoKnight => Some(PieceType.Knight)
    case _           => None

  // ── (score, move) packing for ordering ──────────────────────────
  //
  // For move ordering the search packs a score (Int) and the move
  // (Int) into a single Long so `java.util.Arrays.sort` can sort
  // the whole batch in one call. The score goes in the high 32
  // bits so the natural ascending sort orders by score; callers
  // iterate the sorted array in reverse for descending preference.

  /** Pack a non-negative ordering score, the original insertion
    * index, and a 32-bit move into a single Long for batch sort
    * by `java.util.Arrays.sort`.
    *
    * Bit layout (high to low):
    *   bits 32–63   score (Int)
    *   bits 16–31   (0xFFFF − insertionIdx) — inverted so a LOWER
    *                insertion index produces a HIGHER Long value,
    *                meaning ties on score iterate first-generated
    *                first (preserves the rules-layer move order
    *                under reverse iteration; that order is
    *                tactically meaningful, e.g. knights before
    *                pawns in the opening).
    *   bits 0–15    move encoding (16 bits — comfortably fits the
    *                14 bits actually used by [[encode]]).
    *
    * Iteration: ascending sort then read from `count − 1` down to
    * 0 gives highest score first, with original generation order
    * preserved within ties. */
  inline def pack(score: Int, insertionIdx: Int, move: Int): Long =
    val invertedIdx = (0xFFFF - (insertionIdx & 0xFFFF)).toLong
    (score.toLong << 32) | (invertedIdx << 16) | (move.toLong & 0xFFFFL)

  /** Unpack the move portion of a [[pack]]ed Long. */
  inline def fromPacked(packed: Long): Int = (packed & 0xFFFFL).toInt

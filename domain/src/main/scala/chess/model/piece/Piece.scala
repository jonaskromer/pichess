package chess.model.piece

case class Piece(color: Color, pieceType: PieceType)

object Piece:
  // ── Flyweight cache ─────────────────────────────────────────────
  //
  // There are exactly 12 possible (color, pieceType) combinations
  // (2 × 6). Pre-allocating them as singletons + their Some wrappers
  // means `BoardState.get` — which fires by the million in the bot
  // search hot loop — does zero allocation: it returns one of the
  // 13 cached references (12 pieces × `Some` + `None`).
  //
  // Without this cache, `BoardState.get` accounted for 5.8% of CPU
  // (mostly via `Piece$.apply` + `Some.<init>`) at depth 4 after
  // the rules-sync refactor — see perf-reports/profiles/long/.
  //
  // The case class's synthetic `apply` is intercepted below; the
  // structural-equality semantics of case classes still hold
  // because we always return the canonical instance for each
  // distinct `(color, pieceType)` pair.

  /** Index into the flyweight table: 6 piece types × 2 colors. */
  private inline def idx(color: Color, pt: PieceType): Int =
    pt.ordinal * 2 + color.ordinal

  /** 12 cached `Piece` singletons, indexed by [[idx]]. */
  private val cached: Array[Piece] =
    val arr = new Array[Piece](12)
    PieceType.values.foreach { pt =>
      arr(idx(Color.White, pt)) = new Piece(Color.White, pt)
      arr(idx(Color.Black, pt)) = new Piece(Color.Black, pt)
    }
    arr

  /** Pre-wrapped `Some(Piece)` flyweights so callers that return
    * `Option[Piece]` (e.g. `BoardState.get`) avoid allocating a
    * fresh `Some` on every lookup. */
  val cachedSome: Array[Option[Piece]] = cached.map(Some(_))

  /** Intercept the case-class `apply` so callers always receive the
    * canonical singleton. Same structural-equality behaviour as a
    * non-cached case class — distinct `(color, pieceType)` pairs
    * compare unequal; equal pairs are now `eq`. */
  def apply(color: Color, pieceType: PieceType): Piece =
    cached(idx(color, pieceType))

  /** Convenience for callers that want the flyweight `Some` directly
    * — used by `BoardState.get` to skip the per-call `Some(...)`
    * allocation. */
  def someOf(color: Color, pieceType: PieceType): Option[Piece] =
    cachedSome(idx(color, pieceType))

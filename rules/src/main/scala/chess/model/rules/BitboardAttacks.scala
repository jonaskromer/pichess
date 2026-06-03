package chess.model.rules

/** Precomputed attack tables + bitboard ray walkers used by the
  * bitboard-native predicates in [[MoveValidator]] (Phase 2 of the
  * bitboard migration). The leaper tables are computed once at class
  * load (~2 KB total) and read O(1) per lookup; sliding-piece attacks
  * are computed on demand from a (square, occupancy) pair — naive
  * direction walks via bit ops, no magic bitboards yet (plenty fast
  * for our workload, far simpler code than the magic-mask + index
  * variant).
  *
  * All indices are LERF (see [[chess.model.board.Position.squareIdx]]).
  */
private[rules] object BitboardAttacks:

  // ── Leaper tables (computed once at class load) ──────────────────────

  /** For each square 0..63, bitboard of squares a KNIGHT on that square
    * attacks. Symmetric — knight attacks are unaffected by occupancy. */
  val knightAttacks: Array[Long] = buildKnightAttacks()

  /** For each square 0..63, bitboard of squares a KING on that square
    * attacks (one step in any of 8 directions, bounded by board edges). */
  val kingAttacks: Array[Long] = buildKingAttacks()

  /** For each `target`, bitboard of squares a WHITE pawn would need to
    * be on to attack `target`. `target-7` (pawn 1 col right, 1 row
    * down) and `target-9` (pawn 1 col left, 1 row down), bounded. */
  val whitePawnAttackersOf: Array[Long] = buildWhitePawnAttackersOf()

  /** Symmetric to [[whitePawnAttackersOf]] for BLACK pawn attackers. */
  val blackPawnAttackersOf: Array[Long] = buildBlackPawnAttackersOf()

  // ── Sliding attacks (computed on demand) ─────────────────────────────

  /** All squares a BISHOP at `sq` attacks given `occupancy`. Walks each
    * of 4 diagonal directions, stopping at the first occupied square
    * (which is included — it's a potential capture target). */
  def bishopAttacks(sq: Int, occupancy: Long): Long =
    slidingAttacks(sq, occupancy, BishopDeltaCol, BishopDeltaRow)

  /** Same for a ROOK on the 4 orthogonal directions. */
  def rookAttacks(sq: Int, occupancy: Long): Long =
    slidingAttacks(sq, occupancy, RookDeltaCol, RookDeltaRow)

  // (Queen attacks aren't a separate function — `attackerBitboard` in
  // MoveValidator folds queens into both the bishop and rook intersections
  // with `bishops | queens` / `rooks | queens`, which is faster than a
  // dedicated `queenAttacks` call would be.)

  // ── Internals ────────────────────────────────────────────────────────

  // Parallel arrays instead of `Array[(Int, Int)]` so the inner walk
  // doesn't allocate or box on each direction step.
  private val BishopDeltaCol = Array( 1,  1, -1, -1)
  private val BishopDeltaRow = Array( 1, -1,  1, -1)
  private val RookDeltaCol   = Array( 1, -1,  0,  0)
  private val RookDeltaRow   = Array( 0,  0,  1, -1)

  private def slidingAttacks(
      sq: Int, occupancy: Long, deltaCol: Array[Int], deltaRow: Array[Int]
  ): Long =
    var attacks = 0L
    val col0 = sq % 8
    val row0 = sq / 8
    var i    = 0
    while i < deltaCol.length do
      val dc = deltaCol(i)
      val dr = deltaRow(i)
      var nc = col0 + dc
      var nr = row0 + dr
      var continue = true
      while continue && nc >= 0 && nc < 8 && nr >= 0 && nr < 8 do
        val target = nr * 8 + nc
        val mask   = 1L << target
        attacks |= mask
        if (occupancy & mask) != 0L then continue = false
        else { nc += dc; nr += dr }
      i += 1
    attacks

  private def buildKnightAttacks(): Array[Long] =
    // 8 L-shape offsets
    val dc = Array(-2, -1,  1,  2,  2,  1, -1, -2)
    val dr = Array(-1, -2, -2, -1,  1,  2,  2,  1)
    buildLeaperTable(dc, dr)

  private def buildKingAttacks(): Array[Long] =
    // 8 one-step offsets
    val dc = Array(-1,  0,  1, -1,  1, -1,  0,  1)
    val dr = Array(-1, -1, -1,  0,  0,  1,  1,  1)
    buildLeaperTable(dc, dr)

  private def buildLeaperTable(dc: Array[Int], dr: Array[Int]): Array[Long] =
    val arr = new Array[Long](64)
    var idx = 0
    while idx < 64 do
      val col = idx % 8
      val row = idx / 8
      var bb  = 0L
      var i   = 0
      while i < dc.length do
        val nc = col + dc(i)
        val nr = row + dr(i)
        if nc >= 0 && nc < 8 && nr >= 0 && nr < 8 then
          bb |= 1L << (nr * 8 + nc)
        i += 1
      arr(idx) = bb
      idx += 1
    arr

  private def buildWhitePawnAttackersOf(): Array[Long] =
    val arr = new Array[Long](64)
    var target = 0
    while target < 64 do
      val col = target % 8
      val row = target / 8
      var bb  = 0L
      // White pawn at (col+1, row-1) attacks `target` via the (-1, +1) capture
      if col < 7 && row > 0 then bb |= 1L << (target - 7)
      // White pawn at (col-1, row-1) attacks `target` via the (+1, +1) capture
      if col > 0 && row > 0 then bb |= 1L << (target - 9)
      arr(target) = bb
      target += 1
    arr

  private def buildBlackPawnAttackersOf(): Array[Long] =
    val arr = new Array[Long](64)
    var target = 0
    while target < 64 do
      val col = target % 8
      val row = target / 8
      var bb  = 0L
      // Black pawn at (col-1, row+1) attacks `target` via the (+1, -1) capture
      if col > 0 && row < 7 then bb |= 1L << (target + 7)
      // Black pawn at (col+1, row+1) attacks `target` via the (-1, -1) capture
      if col < 7 && row < 7 then bb |= 1L << (target + 9)
      arr(target) = bb
      target += 1
    arr

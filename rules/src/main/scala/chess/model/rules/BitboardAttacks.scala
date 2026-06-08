package chess.model.rules

/** Precomputed attack tables + bitboard ray walkers used by the
  * bitboard-native predicates in [[MoveValidator]] (Phase 2 of the
  * bitboard migration). The leaper tables are computed once at class
  * load (~2 KB total) and read O(1) per lookup; sliding-piece attacks
  * now use magic bitboards (hash `(occupancy & relevantMask) * magic
  * >>> shift` into a per-square attack table), which collapsed the
  * 157-sample slidingAttacks loop to a 2-instruction lookup in
  * profile.
  *
  * All indices are LERF (see [[chess.model.board.Position.squareIdx]]).
  *
  * Public surface (no `private[rules]`): the bot's eval mobility
  * features need the same attack masks the move generator uses, so
  * this object is part of the rules layer's reusable API — anyone
  * computing "squares this piece attacks given an occupancy" can lean
  * on it without re-deriving the bit math.
  */
object BitboardAttacks:

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

  // ── Pawn attack maps ────────────────────────────────────────────────
  //
  // Given a bitboard of pawns of one color, return the bitboard of every
  // square those pawns collectively attack. The classic two-shift trick
  // — NE/NW for white, SE/SW for black — masks out the file that would
  // wrap to the opposite side. Pure ALU, no table lookups, < 5 ns/call.

  private val FileA: Long = 0x0101010101010101L
  private val FileH: Long = 0x8080808080808080L

  /** Bitboard of every square attacked by the given white-pawn set. */
  inline def whitePawnAttacksFrom(pawns: Long): Long =
    ((pawns & ~FileH) << 9) | ((pawns & ~FileA) << 7)

  /** Bitboard of every square attacked by the given black-pawn set. */
  inline def blackPawnAttacksFrom(pawns: Long): Long =
    ((pawns & ~FileA) >>> 9) | ((pawns & ~FileH) >>> 7)

  // ── Sliding attacks (magic bitboards) ────────────────────────────────

  /** All squares a BISHOP at `sq` attacks given `occupancy`. Hashes
    * the relevant blockers into a per-square attack table — O(1) with
    * a couple of arithmetic ops + one array load. */
  def bishopAttacks(sq: Int, occupancy: Long): Long =
    val idx = (((occupancy & BishopMask(sq)) * BishopMagic(sq)) >>> BishopShift(sq)).toInt
    BishopAttackTable(sq)(idx)

  /** Same for a ROOK on the 4 orthogonal directions. */
  def rookAttacks(sq: Int, occupancy: Long): Long =
    val idx = (((occupancy & RookMask(sq)) * RookMagic(sq)) >>> RookShift(sq)).toInt
    RookAttackTable(sq)(idx)

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

  // ── Magic-bitboard tables (computed once at class load) ──────────────
  //
  // For each square 0..63:
  //   - `*Mask(sq)`         : relevant blocker squares (ray bits minus
  //                            the edge squares — edge bits don't change
  //                            the attack set, so we leave them out to
  //                            shrink the index space).
  //   - `*Shift(sq)`        : `64 - popcount(mask)`, the right-shift
  //                            that lifts the magic hash into the table
  //                            index.
  //   - `*Magic(sq)`        : a 64-bit constant chosen so that for every
  //                            blocker subset of `*Mask(sq)`, the hash
  //                            `(subset * magic) >>> shift` lands in a
  //                            unique slot of the per-square attack
  //                            table.
  //   - `*AttackTable(sq)`  : array of `1 << popcount(mask)` Longs;
  //                            entry `i` = the attack bitboard for the
  //                            blocker configuration that hashes to `i`.
  //
  // Magic constants are searched at init by trial — sparse random Longs
  // until one yields a collision-free hash. Search converges in well
  // under a second per square; total init cost ≈ 100 ms on the bench
  // machine, paid once at JVM start.
  private val BishopMask: Array[Long] = buildBlockerMask(BishopDeltaCol, BishopDeltaRow)
  private val RookMask:   Array[Long] = buildBlockerMask(RookDeltaCol,   RookDeltaRow)
  private val BishopShift: Array[Int]  = new Array[Int](64)
  private val RookShift:   Array[Int]  = new Array[Int](64)
  private val BishopMagic: Array[Long] = new Array[Long](64)
  private val RookMagic:   Array[Long] = new Array[Long](64)
  private val BishopAttackTable: Array[Array[Long]] = new Array[Array[Long]](64)
  private val RookAttackTable:   Array[Array[Long]] = new Array[Array[Long]](64)

  initMagics()

  private def initMagics(): Unit =
    val rng = new java.util.Random(0x6f48dba6c8f2L)
    var sq = 0
    while sq < 64 do
      installMagic(sq, BishopMask(sq), BishopDeltaCol, BishopDeltaRow,
                   BishopShift, BishopMagic, BishopAttackTable, rng)
      installMagic(sq, RookMask(sq), RookDeltaCol, RookDeltaRow,
                   RookShift, RookMagic, RookAttackTable, rng)
      sq += 1

  private def installMagic(
      sq: Int,
      mask: Long,
      deltaCol: Array[Int],
      deltaRow: Array[Int],
      shifts: Array[Int],
      magics: Array[Long],
      tables: Array[Array[Long]],
      rng: java.util.Random,
  ): Unit =
    val bits = java.lang.Long.bitCount(mask)
    val shift = 64 - bits
    val n = 1 << bits
    // Enumerate every blocker subset of `mask` once, compute its
    // canonical attack bitboard via the naive walk, and keep both
    // around for the magic search.
    val occupancies = new Array[Long](n)
    val attacks = new Array[Long](n)
    var i = 0
    while i < n do
      occupancies(i) = indexToOccupancy(i, bits, mask)
      attacks(i) = slidingAttacks(sq, occupancies(i), deltaCol, deltaRow)
      i += 1
    val table = new Array[Long](n)
    // `used` marks slots that have been claimed by some attack value,
    // including 0L (empty attack set, which CAN occur for sliders when
    // every ray is blocked at distance 1 — e.g. a rook in a corner
    // ringed by its own pieces). Using a separate flag instead of
    // `table(idx) == 0L` avoids treating those legitimate empty-attack
    // slots as "free" and silently overwriting them with a different
    // attack later.
    val used = new Array[Boolean](n)
    var found = false
    var magic = 0L
    var attempts = 0
    while !found do
      magic = sparseLong(rng)
      java.util.Arrays.fill(table, 0L)
      java.util.Arrays.fill(used, false)
      var collision = false
      var k = 0
      while !collision && k < n do
        val idx = ((occupancies(k) * magic) >>> shift).toInt
        if !used(idx) then
          table(idx) = attacks(k)
          used(idx) = true
        else if table(idx) != attacks(k) then collision = true
        k += 1
      if !collision then found = true
      attempts += 1
      if attempts > 5000000 then
        throw new RuntimeException(s"Magic search failed at sq=$sq after $attempts attempts")
    shifts(sq) = shift
    magics(sq) = magic
    tables(sq) = table

  /** Build the per-square relevant-blocker mask for a slider whose ray
    * directions are given by `(deltaCol(i), deltaRow(i))`. Walks each
    * direction from `sq` and includes every square along the ray
    * except (a) `sq` itself and (b) edge squares (where the edge bit
    * can't influence what the slider sees on the inner squares). */
  private def buildBlockerMask(deltaCol: Array[Int], deltaRow: Array[Int]): Array[Long] =
    val arr = new Array[Long](64)
    var sq = 0
    while sq < 64 do
      val col0 = sq % 8
      val row0 = sq / 8
      var bb = 0L
      var d = 0
      while d < deltaCol.length do
        val dc = deltaCol(d)
        val dr = deltaRow(d)
        var nc = col0 + dc
        var nr = row0 + dr
        // Stop one step BEFORE the edge in each direction.
        while nc + dc >= 0 && nc + dc < 8 && nr + dr >= 0 && nr + dr < 8 do
          bb |= 1L << (nr * 8 + nc)
          nc += dc
          nr += dr
        d += 1
      arr(sq) = bb
      sq += 1
    arr

  /** Project the bottom `bits` bits of `idx` onto the set positions of
    * `mask` to produce one of the `1 << bits` blocker subsets. */
  private def indexToOccupancy(idx: Int, bits: Int, mask: Long): Long =
    var result = 0L
    var remaining = mask
    var i = 0
    while i < bits do
      val sq = java.lang.Long.numberOfTrailingZeros(remaining)
      remaining &= remaining - 1L
      if ((idx >> i) & 1) != 0 then result |= 1L << sq
      i += 1
    result

  /** Random Long with sparse bit set — the magic-search converges far
    * faster on these because dense candidates collide constantly. */
  private def sparseLong(rng: java.util.Random): Long =
    rng.nextLong() & rng.nextLong() & rng.nextLong()

package chess.bot.engine.internal

/** Precomputed bitboard masks for pawn-structure and king-safety features.
  *
  * All masks are LERF (a1 = 0, h8 = 63), matching every other bitboard in the
  * codebase. Computed once at class load (~600 bytes total across the eight
  * arrays); per-feature lookups are O(1) bit ops.
  *
  * Internal to bot-engine because the masks are purely about eval-feature
  * derivation. The rules layer doesn't need them and shouldn't be coupled to
  * this representation; mobility uses [[chess.model.rules.BitboardAttacks]] for
  * piece attack masks which is a separate concern.
  */
private[engine] object PawnMasks:

  /** Bitboard of every square on file `col` (0 = file 'a', 7 = 'h'). */
  val fileMask: Array[Long] =
    val arr = new Array[Long](8)
    var f = 0
    while f < 8 do
      var bb = 0L
      var r = 0
      while r < 8 do
        bb |= 1L << (r * 8 + f)
        r += 1
      arr(f) = bb
      f += 1
    arr

  /** Union of files (col-1) and (col+1), bounded to 0..7. Used for "is this
    * pawn isolated" / "are there friendly pawns on adjacent files" queries.
    */
  val adjacentFileMask: Array[Long] =
    val arr = new Array[Long](8)
    var f = 0
    while f < 8 do
      var bb = 0L
      if f > 0 then bb |= fileMask(f - 1)
      if f < 7 then bb |= fileMask(f + 1)
      arr(f) = bb
      f += 1
    arr

  /** Bitboard of every square a WHITE pawn at index `sq` "spans" ahead of
    * itself on its own file. Excludes `sq` itself; includes every square from
    * rank+1 to rank 8 on the same file.
    */
  val frontSpanWhite: Array[Long] = buildFrontSpan(white = true)

  /** Symmetric to [[frontSpanWhite]] for black (rank-1 down to rank 1). */
  val frontSpanBlack: Array[Long] = buildFrontSpan(white = false)

  /** Squares an enemy pawn would need to be on to block a WHITE pawn at `sq`
    * from being passed: the front span on its file PLUS the front spans on the
    * two adjacent files. If `passedPawnMaskWhite(sq) & blackPawns == 0`, the
    * white pawn is passed.
    */
  val passedPawnMaskWhite: Array[Long] = buildPassedMask(white = true)

  /** Symmetric to [[passedPawnMaskWhite]] for black pawns. */
  val passedPawnMaskBlack: Array[Long] = buildPassedMask(white = false)

  /** King-zone mask: the 3×3 square around `sq`, clipped to the board. Used for
    * "pawn shield" count and "enemy attackers near king" count.
    */
  val kingZone: Array[Long] = buildKingZone()

  /** "Pawn neighbour" mask: every square on an adjacent file within ±1 rank. A
    * pawn at `sq` is *connected* iff at least one of these squares holds a
    * friendly pawn.
    */
  val pawnNeighbor: Array[Long] = buildPawnNeighbor()

  // ── builders ──────────────────────────────────────────────────────

  private def buildFrontSpan(white: Boolean): Array[Long] =
    val arr = new Array[Long](64)
    var sq = 0
    while sq < 64 do
      val file = sq % 8
      val rank = sq / 8
      var bb = 0L
      if white then
        var r = rank + 1
        while r < 8 do
          bb |= 1L << (r * 8 + file)
          r += 1
      else
        var r = rank - 1
        while r >= 0 do
          bb |= 1L << (r * 8 + file)
          r -= 1
      arr(sq) = bb
      sq += 1
    arr

  private def buildPassedMask(white: Boolean): Array[Long] =
    val arr = new Array[Long](64)
    var sq = 0
    while sq < 64 do
      val file = sq % 8
      val frontSpan = if white then frontSpanWhite(sq) else frontSpanBlack(sq)
      // Front span on the adjacent files at the same and higher ranks.
      // Reuse frontSpan(adjacentSquare) for clean computation.
      var bb = frontSpan
      if file > 0 then
        // Adjacent file to the left: sq - 1 is on the left file, same rank
        if white then bb |= frontSpanWhite(sq - 1) | (1L << (sq - 1))
        else bb |= frontSpanBlack(sq - 1) | (1L << (sq - 1))
      if file < 7 then
        if white then bb |= frontSpanWhite(sq + 1) | (1L << (sq + 1))
        else bb |= frontSpanBlack(sq + 1) | (1L << (sq + 1))
      // Self square is irrelevant — pawn isn't blocking itself.
      bb &= ~(1L << sq)
      arr(sq) = bb
      sq += 1
    arr

  private def buildKingZone(): Array[Long] =
    val arr = new Array[Long](64)
    var sq = 0
    while sq < 64 do
      val file = sq % 8
      val rank = sq / 8
      var bb = 0L
      var df = -1
      while df <= 1 do
        var dr = -1
        while dr <= 1 do
          val nf = file + df
          val nr = rank + dr
          if nf >= 0 && nf < 8 && nr >= 0 && nr < 8 then
            bb |= 1L << (nr * 8 + nf)
          dr += 1
        df += 1
      arr(sq) = bb
      sq += 1
    arr

  private def buildPawnNeighbor(): Array[Long] =
    val arr = new Array[Long](64)
    var sq = 0
    while sq < 64 do
      val file = sq % 8
      val rank = sq / 8
      var bb = 0L
      // Adjacent files, ranks within ±1 of the pawn's rank.
      var dr = -1
      while dr <= 1 do
        val nr = rank + dr
        if nr >= 0 && nr < 8 then
          if file > 0 then bb |= 1L << (nr * 8 + (file - 1))
          if file < 7 then bb |= 1L << (nr * 8 + (file + 1))
        dr += 1
      arr(sq) = bb
      sq += 1
    arr

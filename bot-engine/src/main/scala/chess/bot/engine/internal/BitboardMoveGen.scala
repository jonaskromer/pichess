package chess.bot.engine.internal

import chess.model.board.{GameState, MoveInt}
import chess.model.piece.Color
import chess.model.rules.BitboardAttacks

/** Zero-allocation bitboard move generator.
  *
  * Walks active piece bitboards via `BitboardAttacks` and emits
  * packed `MoveInt`s directly into output buffers. Legality is
  * checked inline with a pure-bitboard "post-move attacker
  * probe": XOR the relevant bits into local Long copies of the
  * piece bitboards + occupancy, compute attackers on the king
  * square against those copies, accept the move iff no enemy
  * piece attacks the king. No `Move` case-class, no `BoardState`
  * copy, no `applyMoveCoreSync` allocation.
  *
  * Special cases handled inline:
  *   * en passant — the captured pawn lives on a different
  *     square than the move target, so we XOR an additional pawn
  *     bit out of the enemy pawn bitboard + occupancy
  *   * promotion — emitted with `MoveInt.PromoQueen` (queens
  *     only, matching the existing generator's contract)
  *   * castling — emitted as a 2-square king step, then
  *     validated by `validateCastling` which checks rights, the
  *     in-between squares are empty, and the king doesn't pass
  *     through / land on an attacked square
  *
  * Contract / return: writes encoded `MoveInt`s into the
  * supplied buffers separately for captures and quiets, returns
  * `(captureCount, quietCount)`. Same shape as
  * `RulesAdapter.fillCapturesAndQuiets` callers expect. */
object BitboardMoveGen:

  def fillCapturesAndQuiets(
      state: GameState,
      capBuf: Array[Int],
      quietBuf: Array[Int],
  ): (Int, Int) =
    val board = state.board
    val white = state.activeColor == Color.White

    val pawns    = if white then board.pawnsW.raw   else board.pawnsB.raw
    val knights  = if white then board.knightsW.raw else board.knightsB.raw
    val bishops  = if white then board.bishopsW.raw else board.bishopsB.raw
    val rooks    = if white then board.rooksW.raw   else board.rooksB.raw
    val queens   = if white then board.queensW.raw  else board.queensB.raw
    val king     = if white then board.kingW.raw    else board.kingB.raw
    val ownOcc   = if white then board.whitePieces.raw else board.blackPieces.raw
    val enemyOcc = if white then board.blackPieces.raw else board.whitePieces.raw
    val occ      = board.occupancy.raw
    val notOwn   = ~ownOcc

    val enemyPawns   = if white then board.pawnsB.raw   else board.pawnsW.raw
    val enemyKnights = if white then board.knightsB.raw else board.knightsW.raw
    val enemyBishops = if white then board.bishopsB.raw else board.bishopsW.raw
    val enemyRooks   = if white then board.rooksB.raw   else board.rooksW.raw
    val enemyQueens  = if white then board.queensB.raw  else board.queensW.raw
    val enemyKing    = if white then board.kingB.raw    else board.kingW.raw

    val kingSq = if king == 0L then -1 else java.lang.Long.numberOfTrailingZeros(king)
    val epToIdx: Int = state.enPassantTarget.fold(-1)(_.squareIdx)
    val epMask: Long = if epToIdx >= 0 then 1L << epToIdx else 0L

    var nc = 0
    var nq = 0

    // ─── Legality check via bitboard XOR + attacker probe ────────
    //
    // For each candidate move, simulate the move by computing
    // post-move bitboards locally:
    //   * own occupancy: `from` bit cleared, `to` bit set
    //   * enemy occupancy: if `to` was occupied by an enemy
    //     piece, that piece is captured (its bit cleared in
    //     whichever enemy-piece bitboard had it)
    //   * en passant: additionally clear the actually-captured
    //     pawn's bit (on a different square than `to`)
    // Then compute the attacker mask on the moving side's king
    // square; if it's empty, the move is legal.
    //
    // No allocation: every var is a `Long` on the stack.
    inline def isLegal(
        fromIdx: Int,
        toIdx: Int,
        isKing: Boolean,
        isEpCapture: Boolean,
    ): Boolean =
      val fromMask = 1L << fromIdx
      val toMask   = 1L << toIdx
      // Post-move own occupancy: vacate `from`, occupy `to`.
      var occAfter = (occ & ~fromMask) | toMask
      // Enemy bitboards — strip the captured piece if any.
      var ep = enemyPawns
      var en = enemyKnights
      var eb = enemyBishops
      var er = enemyRooks
      var eq = enemyQueens
      var ek = enemyKing
      val captureMask =
        if isEpCapture then
          // The captured pawn is one square "behind" the
          // destination from the moving side's perspective.
          val capSq = if white then toIdx - 8 else toIdx + 8
          val capMaskLocal = 1L << capSq
          // Remove the captured pawn from both the enemy pawns
          // bitboard and the overall occupancy (it's not on `to`).
          ep &= ~capMaskLocal
          occAfter = occAfter & ~capMaskLocal
          capMaskLocal
        else
          // Normal capture: enemy piece on `to`. Remove from
          // whichever enemy-piece bitboard owns that square.
          if (enemyOcc & toMask) != 0L then
            if      (ep & toMask) != 0L then ep &= ~toMask
            else if (en & toMask) != 0L then en &= ~toMask
            else if (eb & toMask) != 0L then eb &= ~toMask
            else if (er & toMask) != 0L then er &= ~toMask
            else if (eq & toMask) != 0L then eq &= ~toMask
            else if (ek & toMask) != 0L then ek &= ~toMask
          toMask
      val kingSqAfter = if isKing then toIdx else kingSq
      // Silence "unused" warning: `captureMask` is here to
      // document intent + provide a hook for future selective
      // legality optimisations (pin detection, etc.).
      val _ = captureMask
      // Kingless states (some FEN-driven tests strip the king
      // to isolate a tactical motif) can't be checked — there's
      // no king to attack. Accept every move; the search treats
      // those positions as terminal anyway.
      if kingSqAfter < 0 then true
      else
        val pawnAtkMask =
          if white then BitboardAttacks.blackPawnAttackersOf(kingSqAfter)
          else          BitboardAttacks.whitePawnAttackersOf(kingSqAfter)
        val bAtk = BitboardAttacks.bishopAttacks(kingSqAfter, occAfter)
        val rAtk = BitboardAttacks.rookAttacks(kingSqAfter, occAfter)
        val attackers =
          (ep & pawnAtkMask)                                |
          (en & BitboardAttacks.knightAttacks(kingSqAfter)) |
          ((eb | eq) & bAtk)                                 |
          ((er | eq) & rAtk)                                 |
          (ek & BitboardAttacks.kingAttacks(kingSqAfter))
        attackers == 0L

    inline def squareAttacked(sq: Int, occMask: Long): Boolean =
      val pawnAtkMask =
        if white then BitboardAttacks.blackPawnAttackersOf(sq)
        else          BitboardAttacks.whitePawnAttackersOf(sq)
      val bAtk = BitboardAttacks.bishopAttacks(sq, occMask)
      val rAtk = BitboardAttacks.rookAttacks(sq, occMask)
      val attackers =
        (enemyPawns & pawnAtkMask)                                 |
        (enemyKnights & BitboardAttacks.knightAttacks(sq))         |
        ((enemyBishops | enemyQueens) & bAtk)                       |
        ((enemyRooks | enemyQueens) & rAtk)                         |
        (enemyKing & BitboardAttacks.kingAttacks(sq))
      attackers != 0L

    /** Castling-specific validation: rights still present, the
      * in-between squares are empty, king not currently in check,
      * king doesn't traverse / land on an attacked square. */
    inline def isCastlingLegal(fromIdx: Int, toIdx: Int): Boolean =
      val rights = state.castlingRights
      val rank   = if white then 0 else 7
      val kingside = toIdx == 6 + rank * 8
      val rightOk =
        if white && kingside       then rights.whiteKingSide
        else if white              then rights.whiteQueenSide
        else if kingside           then rights.blackKingSide
        else                            rights.blackQueenSide
      if !rightOk then false
      else
        // Squares between king and rook must be empty.
        val betweenMask =
          if kingside then (1L << (5 + rank * 8)) | (1L << (6 + rank * 8))
          else
            (1L << (1 + rank * 8)) |
              (1L << (2 + rank * 8)) |
              (1L << (3 + rank * 8))
        if (occ & betweenMask) != 0L then false
        // King not currently in check (attacker check at fromIdx).
        else if squareAttacked(fromIdx, occ) then false
        else
          // King-side: passes through f-file, lands on g-file.
          // Queen-side: passes through d-file, lands on c-file.
          val passSq = if kingside then 5 + rank * 8 else 3 + rank * 8
          val landSq = toIdx
          // For the through-check probe, the king is still at
          // `fromIdx`, but conceptually moves through `passSq`.
          // We need the occupancy with the king removed for slider
          // attacks from past the king.
          val occNoKing = occ ^ (1L << fromIdx)
          if squareAttacked(passSq, occNoKing) then false
          else if squareAttacked(landSq, occNoKing) then false
          else true

    inline def tryEmit(
        from: Int,
        to: Int,
        promo: Int,
        capture: Boolean,
        isKing: Boolean,
        isEpCapture: Boolean,
        isCastling: Boolean,
    ): Unit =
      val legal =
        if isCastling then isCastlingLegal(from, to)
        else isLegal(from, to, isKing, isEpCapture)
      if legal then
        val encoded = MoveInt.encode(from, to, promo)
        if capture then
          capBuf(nc) = encoded
          nc += 1
        else
          quietBuf(nq) = encoded
          nq += 1

    // ── Pawn moves ────────────────────────────────────────────
    val pushStep   = if white then 8 else -8
    val startRank  = if white then 1 else 6
    val promoRank  = if white then 7 else 0
    var p = pawns
    while p != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(p)
      p &= p - 1L
      val pushTo = from + pushStep
      if pushTo >= 0 && pushTo < 64 && (occ & (1L << pushTo)) == 0L then
        val pushRank = pushTo >>> 3
        if pushRank == promoRank then
          tryEmit(from, pushTo, MoveInt.PromoQueen,
                  capture = false, isKing = false, isEpCapture = false, isCastling = false)
        else
          tryEmit(from, pushTo, MoveInt.NoPromotion,
                  capture = false, isKing = false, isEpCapture = false, isCastling = false)
          if (from >>> 3) == startRank then
            val pushTo2 = pushTo + pushStep
            if (occ & (1L << pushTo2)) == 0L then
              tryEmit(from, pushTo2, MoveInt.NoPromotion,
                      capture = false, isKing = false,
                      isEpCapture = false, isCastling = false)
      val attacks   = pawnAttacks(from, white)
      val captureTo = attacks & (enemyOcc | epMask)
      var cb = captureTo
      while cb != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(cb)
        cb &= cb - 1L
        val toRank   = to >>> 3
        val isEp     = (epMask & (1L << to)) != 0L
        if toRank == promoRank then
          tryEmit(from, to, MoveInt.PromoQueen,
                  capture = true, isKing = false, isEpCapture = isEp, isCastling = false)
        else
          tryEmit(from, to, MoveInt.NoPromotion,
                  capture = true, isKing = false, isEpCapture = isEp, isCastling = false)

    // ── Knight moves ──────────────────────────────────────────
    var n = knights
    while n != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(n)
      n &= n - 1L
      var dests = BitboardAttacks.knightAttacks(from) & notOwn
      while dests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(dests)
        dests &= dests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(from, to, MoveInt.NoPromotion,
                capture, isKing = false, isEpCapture = false, isCastling = false)

    // ── Bishop + queen diagonal moves ─────────────────────────
    var diag = bishops | queens
    while diag != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(diag)
      diag &= diag - 1L
      var dests = BitboardAttacks.bishopAttacks(from, occ) & notOwn
      while dests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(dests)
        dests &= dests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(from, to, MoveInt.NoPromotion,
                capture, isKing = false, isEpCapture = false, isCastling = false)

    // ── Rook + queen orthogonal moves ─────────────────────────
    var ortho = rooks | queens
    while ortho != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(ortho)
      ortho &= ortho - 1L
      var dests = BitboardAttacks.rookAttacks(from, occ) & notOwn
      while dests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(dests)
        dests &= dests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(from, to, MoveInt.NoPromotion,
                capture, isKing = false, isEpCapture = false, isCastling = false)

    // ── King moves + castling ─────────────────────────────────
    if king != 0L then
      val kFrom = kingSq
      var kDests = BitboardAttacks.kingAttacks(kFrom) & notOwn
      while kDests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(kDests)
        kDests &= kDests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(kFrom, to, MoveInt.NoPromotion,
                capture, isKing = true, isEpCapture = false, isCastling = false)
      val rank = if white then 0 else 7
      val kingStart = 4 + rank * 8
      if kFrom == kingStart then
        tryEmit(kFrom, 6 + rank * 8, MoveInt.NoPromotion,
                capture = false, isKing = true, isEpCapture = false, isCastling = true)
        tryEmit(kFrom, 2 + rank * 8, MoveInt.NoPromotion,
                capture = false, isKing = true, isEpCapture = false, isCastling = true)

    (nc, nq)

  /** Bitboard of squares a pawn at `from` attacks (capture squares
    * only, no forward push). */
  private inline def pawnAttacks(from: Int, white: Boolean): Long =
    val col = from % 8
    var bb = 0L
    if white then
      if col > 0 then bb |= 1L << (from + 7)
      if col < 7 then bb |= 1L << (from + 9)
    else
      if col > 0 then bb |= 1L << (from - 9)
      if col < 7 then bb |= 1L << (from - 7)
    bb

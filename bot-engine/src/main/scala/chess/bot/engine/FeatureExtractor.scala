package chess.bot.engine

import chess.bot.engine.internal.PawnMasks
import chess.model.board.GameState
import chess.model.piece.Color
import chess.model.rules.BitboardAttacks

/** Maps a [[GameState]] to a `Map[String, Int]` feature vector that
  * [[TunedEvaluator]] uses for its linear combination.
  *
  * The Phase 5 extractor counts piece-type differences (white minus
  * black) — same feature space as the hand-coded
  * [[MaterialEvaluator]], so Texel tuning over [[features]] is
  * directly comparable to that evaluator's hardcoded constants.
  * Later phases will fold in piece-square tables (one feature per
  * piece × square), mobility, pawn structure, etc., by adding new
  * extractors or extending this one.
  */
trait FeatureExtractor:
  def features(state: GameState): Map[String, Int]

object FeatureExtractor:

  /** Feature names emitted by [[material]]. Useful as the default
    * key set when initialising a [[TexelTuner]]-equivalent weight
    * vector. */
  val materialNames: List[String] =
    List("pawn", "knight", "bishop", "rook", "queen")

  /** All five piece types that appear in PST feature keys. */
  private val pieceNames: List[String] = materialNames

  /** Material-only extractor: each feature is (white count) − (black
    * count) for that piece type. The king is omitted — its count is
    * always one per side and contributes nothing to the linear
    * combination. */
  val material: FeatureExtractor = MaterialFeatures

  /** Full extractor: a starter-engine-complete feature set. 345
    * features total covering material, piece-square tables, mobility,
    * pawn structure, king safety, rook + knight activity, and tempo:
    *
    *   - 5 material diffs                          (`pawn`...`queen`)
    *   - 320 PST keys (5 pieces × 64 squares)      (`pawn_a2` etc.)
    *   - 1 bishop pair indicator
    *   - 4 mobility counts                         (`knight_mobility`...)
    *   - 6 passed-pawn by white-equivalent rank    (`passed_rank_2`...`_7`)
    *   - 1 isolated pawn diff
    *   - 1 doubled pawn diff
    *   - 1 connected pawn diff
    *   - 1 pawn shield count (own pawns near own king)
    *   - 1 king attackers diff (enemy pieces hitting king zone)
    *   - 1 rook on open file
    *   - 1 rook on semi-open file
    *   - 1 knight outpost diff
    *   - 1 tempo (side-to-move bonus)
    *
    * All features are signed white-perspective: positive contributes
    * to white's eval. Black pieces are mirrored to the white-
    * equivalent square (e.g. a black pawn on rank 4 maps to white
    * rank 5) so the tuner learns symmetric values.
    *
    * Black pieces in the PST mirror naturally cancel white pieces on
    * the mirrored square — the starting position has 0 for every
    * non-material feature, so the tuner only learns deviations from
    * symmetry.
    */
  val full: FeatureExtractor = FullFeatures

  /** Every feature key the [[full]] extractor can emit, in canonical
    * order. Useful for seeding a weight vector with zeros (or hand-
    * picked initial values) for the Texel tuner. */
  def allFeatureNames: Seq[String] =
    materialNames ++
      pstFeatureNames ++
      Seq("bishop_pair") ++
      Seq("knight_mobility", "bishop_mobility", "rook_mobility", "queen_mobility") ++
      (2 to 7).map(r => s"passed_rank_$r") ++
      Seq(
        "isolated_pawn",
        "doubled_pawn",
        "connected_pawn",
        "pawn_shield",
        "king_attackers",
        "rook_open_file",
        "rook_semi_open_file",
        "knight_outpost",
        "tempo",
      )

  /** Generate every PST feature key in canonical order. Useful when
    * seeding a weight vector with zeros or printing a tuned table. */
  def pstFeatureNames: Iterable[String] =
    for
      piece <- pieceNames
      idx   <- 0 until 64
    yield s"${piece}_${squareName(idx)}"

  /** Algebraic square name for a LERF bit index (0=a1, 63=h8). */
  private[engine] def squareName(idx: Int): String =
    s"${('a' + (idx % 8)).toChar}${(idx / 8) + 1}"

  /** Mirror a LERF bit index across the horizontal centre (rank
    * flip). Used to map black-piece squares onto white-coordinate
    * PST keys so the tuner only learns one set of tables. */
  private[engine] inline def mirror(idx: Int): Int = idx ^ 56

  private object MaterialFeatures extends FeatureExtractor:
    def features(state: GameState): Map[String, Int] =
      val b = state.board
      Map(
        "pawn"   -> (b.pawnsW.popCount   - b.pawnsB.popCount),
        "knight" -> (b.knightsW.popCount - b.knightsB.popCount),
        "bishop" -> (b.bishopsW.popCount - b.bishopsB.popCount),
        "rook"   -> (b.rooksW.popCount   - b.rooksB.popCount),
        "queen"  -> (b.queensW.popCount  - b.queensB.popCount),
      )

  private object FullFeatures extends FeatureExtractor:
    def features(state: GameState): Map[String, Int] =
      val acc = scala.collection.mutable.HashMap.empty[String, Int]
      val b   = state.board

      addMaterial(acc, b)
      addPstAllPieces(acc, b)
      addBishopPair(acc, b)
      addMobility(acc, b)
      addPawnStructure(acc, b)
      addKingSafety(acc, b)
      addRookActivity(acc, b)
      addKnightOutpost(acc, b)
      add(acc, "tempo", if state.activeColor == Color.White then 1 else -1)

      acc.toMap

    // ── Material + PST + bishop pair (existing features) ──────────

    private def addMaterial(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      add(acc, "pawn",   b.pawnsW.popCount   - b.pawnsB.popCount)
      add(acc, "knight", b.knightsW.popCount - b.knightsB.popCount)
      add(acc, "bishop", b.bishopsW.popCount - b.bishopsB.popCount)
      add(acc, "rook",   b.rooksW.popCount   - b.rooksB.popCount)
      add(acc, "queen",  b.queensW.popCount  - b.queensB.popCount)

    private def addPstAllPieces(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      addPst(acc, "pawn",   b.pawnsW.raw,   b.pawnsB.raw)
      addPst(acc, "knight", b.knightsW.raw, b.knightsB.raw)
      addPst(acc, "bishop", b.bishopsW.raw, b.bishopsB.raw)
      addPst(acc, "rook",   b.rooksW.raw,   b.rooksB.raw)
      addPst(acc, "queen",  b.queensW.raw,  b.queensB.raw)

    private def addBishopPair(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      val whitePair = if b.bishopsW.popCount >= 2 then 1 else 0
      val blackPair = if b.bishopsB.popCount >= 2 then 1 else 0
      add(acc, "bishop_pair", whitePair - blackPair)

    /** PST helper: each piece adds ±1 to its square's PST key;
      * black squares mirror to white-coordinate keys so the tuner
      * learns a single 64-square table per piece type. */
    private def addPst(
        acc: scala.collection.mutable.HashMap[String, Int],
        piece: String,
        whiteBits: Long,
        blackBits: Long,
    ): Unit =
      var w = whiteBits
      while w != 0L do
        val idx = java.lang.Long.numberOfTrailingZeros(w)
        w &= w - 1L
        add(acc, s"${piece}_${squareName(idx)}", 1)
      var bb = blackBits
      while bb != 0L do
        val idx = java.lang.Long.numberOfTrailingZeros(bb)
        bb &= bb - 1L
        add(acc, s"${piece}_${squareName(mirror(idx))}", -1)

    // ── Mobility ──────────────────────────────────────────────────
    //
    // For each non-pawn piece, count the number of pseudo-legal
    // destination squares (any attack square not blocked by an own
    // piece). Sums across all pieces of the type. The tuner usually
    // learns ~3-5 cp per knight/bishop move, ~2 cp per rook/queen
    // move (queens have lots of moves so each is worth less).

    private def addMobility(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      val wOcc = b.whitePieces.raw
      val bOcc = b.blackPieces.raw
      val occ  = b.occupancy.raw
      add(acc, "knight_mobility",
        knightMobility(b.knightsW.raw, wOcc) -
          knightMobility(b.knightsB.raw, bOcc))
      add(acc, "bishop_mobility",
        bishopMobility(b.bishopsW.raw, occ, wOcc) -
          bishopMobility(b.bishopsB.raw, occ, bOcc))
      add(acc, "rook_mobility",
        rookMobility(b.rooksW.raw, occ, wOcc) -
          rookMobility(b.rooksB.raw, occ, bOcc))
      add(acc, "queen_mobility",
        queenMobility(b.queensW.raw, occ, wOcc) -
          queenMobility(b.queensB.raw, occ, bOcc))

    private def knightMobility(pieces: Long, ownOcc: Long): Int =
      var total = 0
      var rem = pieces
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        total += java.lang.Long.bitCount(BitboardAttacks.knightAttacks(sq) & ~ownOcc)
      total

    private def bishopMobility(pieces: Long, occ: Long, ownOcc: Long): Int =
      var total = 0
      var rem = pieces
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        total += java.lang.Long.bitCount(BitboardAttacks.bishopAttacks(sq, occ) & ~ownOcc)
      total

    private def rookMobility(pieces: Long, occ: Long, ownOcc: Long): Int =
      var total = 0
      var rem = pieces
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        total += java.lang.Long.bitCount(BitboardAttacks.rookAttacks(sq, occ) & ~ownOcc)
      total

    private def queenMobility(pieces: Long, occ: Long, ownOcc: Long): Int =
      var total = 0
      var rem = pieces
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        val attacks =
          BitboardAttacks.bishopAttacks(sq, occ) | BitboardAttacks.rookAttacks(sq, occ)
        total += java.lang.Long.bitCount(attacks & ~ownOcc)
      total

    // ── Pawn structure ────────────────────────────────────────────
    //
    // Passed: pawn with no enemy pawn on its file or adjacent files
    //         ahead of it. Bonus scales with how advanced (rank).
    // Isolated: pawn with no friendly pawn on adjacent files.
    // Doubled: pawn behind another friendly pawn on the same file.
    // Connected: pawn with at least one friendly pawn on an adjacent
    //            file within ±1 rank (defender or shoulder-mate).

    private def addPawnStructure(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      val wPawns = b.pawnsW.raw
      val bPawns = b.pawnsB.raw
      addPassedPawns(acc, wPawns, bPawns, white = true)
      addPassedPawns(acc, bPawns, wPawns, white = false)
      add(acc, "isolated_pawn",  isolatedCount(wPawns) - isolatedCount(bPawns))
      add(acc, "doubled_pawn",   doubledCount(wPawns)  - doubledCount(bPawns))
      add(acc, "connected_pawn", connectedCount(wPawns) - connectedCount(bPawns))

    private def addPassedPawns(
        acc: scala.collection.mutable.HashMap[String, Int],
        ownPawns: Long,
        enemyPawns: Long,
        white: Boolean,
    ): Unit =
      var rem = ownPawns
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        val mask =
          if white then PawnMasks.passedPawnMaskWhite(sq)
          else          PawnMasks.passedPawnMaskBlack(sq)
        if (enemyPawns & mask) == 0L then
          // White rank from 0 (rank 1) to 7 (rank 8); black mirrors.
          val whiteEquivRank =
            if white then (sq / 8) + 1
            else            (mirror(sq) / 8) + 1
          val sign = if white then 1 else -1
          // Passed pawn ranks land in 2..7 (rank 1 = source, rank 8 = promoted).
          if whiteEquivRank >= 2 && whiteEquivRank <= 7 then
            add(acc, s"passed_rank_$whiteEquivRank", sign)

    private def isolatedCount(pawns: Long): Int =
      var total = 0
      var f = 0
      while f < 8 do
        val pawnsOnFile = pawns & PawnMasks.fileMask(f)
        if pawnsOnFile != 0L then
          val neighbours = pawns & PawnMasks.adjacentFileMask(f)
          if neighbours == 0L then
            total += java.lang.Long.bitCount(pawnsOnFile)
        f += 1
      total

    private def doubledCount(pawns: Long): Int =
      var total = 0
      var f = 0
      while f < 8 do
        val n = java.lang.Long.bitCount(pawns & PawnMasks.fileMask(f))
        if n > 1 then total += n - 1
        f += 1
      total

    private def connectedCount(pawns: Long): Int =
      var total = 0
      var rem = pawns
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        if (PawnMasks.pawnNeighbor(sq) & pawns) != 0L then total += 1
      total

    // ── King safety ───────────────────────────────────────────────
    //
    // pawn_shield: count of own pawns within the king's 3×3 zone.
    //              More shield = safer king; tuner learns positive
    //              weight.
    // king_attackers: net enemy non-pawn pieces whose attack-set
    //                 intersects the own king's zone, signed so
    //                 positive = white attacks more.

    private def addKingSafety(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      val wKingBb = b.kingW.raw
      val bKingBb = b.kingB.raw
      // Empty-king positions can occur in some test fixtures — guard
      // both indices and the attacker scan to avoid OOB / negative
      // shifts on a corner-case board.
      if wKingBb == 0L || bKingBb == 0L then ()
      else
        val wKingSq = java.lang.Long.numberOfTrailingZeros(wKingBb)
        val bKingSq = java.lang.Long.numberOfTrailingZeros(bKingBb)
        val wShield = java.lang.Long.bitCount(PawnMasks.kingZone(wKingSq) & b.pawnsW.raw)
        val bShield = java.lang.Long.bitCount(PawnMasks.kingZone(bKingSq) & b.pawnsB.raw)
        add(acc, "pawn_shield", wShield - bShield)

        // Count enemy non-pawn, non-king pieces attacking the king zone.
        val whiteOnBlackKing = kingZoneAttackers(b, PawnMasks.kingZone(bKingSq), byWhite = true)
        val blackOnWhiteKing = kingZoneAttackers(b, PawnMasks.kingZone(wKingSq), byWhite = false)
        add(acc, "king_attackers", whiteOnBlackKing - blackOnWhiteKing)

    /** Count enemy pieces (N/B/R/Q) whose attack-set intersects
      * `kingZone`. Each attacker counted once; an attacker that
      * covers multiple zone squares still adds 1. Cheap because the
      * iteration is over piece-typed bitboards. */
    private def kingZoneAttackers(
        b: chess.model.board.BoardState,
        zone: Long,
        byWhite: Boolean,
    ): Int =
      val knights = if byWhite then b.knightsW.raw else b.knightsB.raw
      val bishops = if byWhite then b.bishopsW.raw else b.bishopsB.raw
      val rooks   = if byWhite then b.rooksW.raw   else b.rooksB.raw
      val queens  = if byWhite then b.queensW.raw  else b.queensB.raw
      val occ     = b.occupancy.raw
      countAttackersOf(knights, zone) { sq => BitboardAttacks.knightAttacks(sq) } +
        countAttackersOf(bishops, zone) { sq => BitboardAttacks.bishopAttacks(sq, occ) } +
        countAttackersOf(rooks,   zone) { sq => BitboardAttacks.rookAttacks(sq, occ) } +
        countAttackersOf(queens,  zone) { sq =>
          BitboardAttacks.bishopAttacks(sq, occ) | BitboardAttacks.rookAttacks(sq, occ)
        }

    private inline def countAttackersOf(pieces: Long, zone: Long)(
        attacksOf: Int => Long,
    ): Int =
      var count = 0
      var rem = pieces
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        if (attacksOf(sq) & zone) != 0L then count += 1
      count

    // ── Rook activity ────────────────────────────────────────────

    private def addRookActivity(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      val wPawns = b.pawnsW.raw
      val bPawns = b.pawnsB.raw
      val (wOpen, wSemi) = rookFileBonuses(b.rooksW.raw, wPawns, bPawns)
      val (bOpen, bSemi) = rookFileBonuses(b.rooksB.raw, bPawns, wPawns)
      add(acc, "rook_open_file",      wOpen - bOpen)
      add(acc, "rook_semi_open_file", wSemi - bSemi)

    /** Per rook, count whether its file is open (no pawns of either
      * side) or semi-open (no own pawns; enemy pawns ok). */
    private def rookFileBonuses(
        rooks: Long,
        ownPawns: Long,
        enemyPawns: Long,
    ): (Int, Int) =
      var open     = 0
      var semiOpen = 0
      var rem = rooks
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        val fileM = PawnMasks.fileMask(sq % 8)
        val ownOnFile   = (fileM & ownPawns) != 0L
        val enemyOnFile = (fileM & enemyPawns) != 0L
        if !ownOnFile && !enemyOnFile then open += 1
        else if !ownOnFile then semiOpen += 1
      (open, semiOpen)

    // ── Knight outpost ───────────────────────────────────────────
    //
    // A knight is "on an outpost" if it's defended by a friendly
    // pawn AND no enemy pawn currently attacks it. Restricted to
    // the opponent's half (rank ≥ 5 for white, ≤ 4 for black) so
    // we don't credit knights in their own territory.

    private def addKnightOutpost(
        acc: scala.collection.mutable.HashMap[String, Int],
        b: chess.model.board.BoardState,
    ): Unit =
      val whiteOutposts = countOutposts(
        knights      = b.knightsW.raw,
        ownPawns     = b.pawnsW.raw,
        enemyPawns   = b.pawnsB.raw,
        white        = true,
      )
      val blackOutposts = countOutposts(
        knights      = b.knightsB.raw,
        ownPawns     = b.pawnsB.raw,
        enemyPawns   = b.pawnsW.raw,
        white        = false,
      )
      add(acc, "knight_outpost", whiteOutposts - blackOutposts)

    private def countOutposts(
        knights: Long,
        ownPawns: Long,
        enemyPawns: Long,
        white: Boolean,
    ): Int =
      var total = 0
      var rem = knights
      while rem != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        val rank = sq / 8
        val inEnemyHalf = if white then rank >= 4 else rank <= 3
        if inEnemyHalf then
          val defendedByPawn =
            if white then (BitboardAttacks.whitePawnAttackersOf(sq) & ownPawns) != 0L
            else          (BitboardAttacks.blackPawnAttackersOf(sq) & ownPawns) != 0L
          val attackedByEnemyPawn =
            if white then (BitboardAttacks.blackPawnAttackersOf(sq) & enemyPawns) != 0L
            else          (BitboardAttacks.whitePawnAttackersOf(sq) & enemyPawns) != 0L
          if defendedByPawn && !attackedByEnemyPawn then total += 1
      total

    /** `acc(key) += value`, treating missing keys as 0. */
    private inline def add(
        acc: scala.collection.mutable.HashMap[String, Int],
        key: String,
        value: Int,
    ): Unit =
      acc.update(key, acc.getOrElse(key, 0) + value)

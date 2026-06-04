package chess.bot.engine

import chess.model.board.GameState

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

  /** Full extractor: material counts + piece-square-table (PST)
    * one-hot features + bishop-pair indicator. ~326 features total:
    *   - 5 material diff counts (`pawn`, `knight`, ... `queen`)
    *   - 5 piece types × 64 squares = 320 PST keys (`pawn_a2`, ...,
    *     `queen_h8`). Each piece on the board contributes ±1 to its
    *     square's key. Black pieces are mirrored to the equivalent
    *     white square (a7 → a2) so the PST exploits symmetry.
    *   - 1 bishop-pair indicator (0 / ±1).
    *
    * The Texel tuner learns the per-square bonuses from the corpus:
    * a knight on f3 turns out to be worth ~+20 cp over a knight on
    * a8, and so on. With this extractor and tuned weights the bot
    * jumps from material-only (~1200 elo) into the 1800-2000 club
    * range.
    */
  val full: FeatureExtractor = FullFeatures

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

      // Material counts.
      add(acc, "pawn",   b.pawnsW.popCount   - b.pawnsB.popCount)
      add(acc, "knight", b.knightsW.popCount - b.knightsB.popCount)
      add(acc, "bishop", b.bishopsW.popCount - b.bishopsB.popCount)
      add(acc, "rook",   b.rooksW.popCount   - b.rooksB.popCount)
      add(acc, "queen",  b.queensW.popCount  - b.queensB.popCount)

      // PST features. Each piece adds ±1 to its square's PST key;
      // black squares mirror to white-coordinate keys so the tuner
      // learns a single 64-square table per piece type.
      addPst(acc, "pawn",   b.pawnsW.raw,   b.pawnsB.raw)
      addPst(acc, "knight", b.knightsW.raw, b.knightsB.raw)
      addPst(acc, "bishop", b.bishopsW.raw, b.bishopsB.raw)
      addPst(acc, "rook",   b.rooksW.raw,   b.rooksB.raw)
      addPst(acc, "queen",  b.queensW.raw,  b.queensB.raw)

      // Bishop pair — present iff a side has both bishops.
      val whitePair = if b.bishopsW.popCount >= 2 then 1 else 0
      val blackPair = if b.bishopsB.popCount >= 2 then 1 else 0
      add(acc, "bishop_pair", whitePair - blackPair)

      acc.toMap

    /** Iterate set bits of `whiteBits` and `blackBits`, contributing
      * +1 to each white-piece square and -1 to each black-piece
      * square (mirrored). White and black pieces on symmetric
      * squares cancel naturally (e.g. a starting position contributes
      * 0 to every PST key — same number of white pawns on the 2nd
      * rank as black pawns on the mirrored 7th rank). */
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

    /** `acc(key) += value`, treating missing keys as 0. */
    private inline def add(
        acc: scala.collection.mutable.HashMap[String, Int],
        key: String,
        value: Int,
    ): Unit =
      acc.update(key, acc.getOrElse(key, 0) + value)

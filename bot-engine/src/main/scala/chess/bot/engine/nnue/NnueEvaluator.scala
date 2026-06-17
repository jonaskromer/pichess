package chess.bot.engine.nnue

import java.nio.{ByteBuffer, ByteOrder}

import chess.bot.engine.Evaluator
import chess.model.board.{Bitboard, BoardLike, PositionView}
import chess.model.piece.Color

/** NNUE inference for pichess — perspective net `(768 -> HiddenSize) x 2 -> 1`,
  * int16 quantized.
  *
  * Network shape mirrors what `train_nnue.py` (PyTorch) and
  * `examples/pichess.rs` (Bullet) both emit, so this loader works for either
  * backend. Byte layout (little-endian i16):
  *
  *   1. feature_weights — column-major `HiddenSize × 768`, QA scale. Stored as
  *      768 accumulator-shaped columns of length HiddenSize. 2. feature_bias —
  *      `HiddenSize` i16s, QA scale. 3. output_weights — `2 × HiddenSize` i16s,
  *      QB scale. 4. output_bias — one i16, `QA × QB` scale.
  *
  * ## Accumulators (efficiently updatable)
  *
  * The two perspective accumulators are stored **per colour** — a
  * White-perspective and a Black-perspective accumulator — which are
  * **independent of whose turn it is**. Each is `feature_bias` plus one column
  * per piece, indexed by [[whitePerspIdx]] / [[blackPerspIdx]]. Because they're
  * turn-independent they can be maintained *incrementally* across make/unmake:
  * a move only changes a handful of features, so [[applyDiff]] adds/subtracts
  * just those columns instead of rebuilding from scratch ([[refreshInto]]). At
  * eval time [[evaluateFrom]] picks `stm = own-colour perspective, ntm = other`
  * and runs the output layer.
  *
  * `evaluate(state)` (the [[Evaluator]] entry point) is the from-scratch path —
  * `freshAccumulator → refreshInto → evaluateFrom` — kept for callers that
  * don't thread an accumulator (and as the correctness oracle the incremental
  * path is tested against).
  */
final class NnueEvaluator private (
    private val featureWeights: Array[
      Int
    ], // 768 × HiddenSize, column-major (int16 widened to int32 so the accumulator loops auto-vectorize)
    private val featureBias: Array[Short], // HiddenSize
    private val outputWeights: Array[Short], // 2 × HiddenSize
    private val outputBias: Short
) extends Evaluator:

  import NnueEvaluator.*

  /** From-scratch eval — rebuilds the accumulator then runs the output layer.
    * Identical result to the old implementation (this is just the accumulator
    * path composed).
    */
  override def evaluate(state: PositionView): Int =
    val acc = freshAccumulator()
    refreshInto(acc, state.board)
    evaluateFrom(acc, state.activeColor)

  // -- Incremental-eval capability (see [[Evaluator]]) --
  override def incrementalNet: Option[NnueEvaluator] = Some(this)
  override def evaluateWith(acc: NnueAccumulator, state: PositionView): Int =
    evaluateFrom(acc, state.activeColor)

  // ---------------------------------------------------------------------------
  // Incremental accumulator API (the search hot path uses these)
  // ---------------------------------------------------------------------------

  /** A reusable accumulator, both perspectives initialised to the bias. */
  def freshAccumulator(): NnueAccumulator =
    new NnueAccumulator(biasArray(), biasArray())

  /** Rebuild both per-colour accumulators from scratch for `board`. */
  def refreshInto(acc: NnueAccumulator, board: BoardLike): Unit =
    resetToBias(acc.white)
    resetToBias(acc.black)
    var pc = 0
    while pc < 12 do
      val color = pc / 6 // 0 = white, 1 = black
      val ord = pc % 6 // 0=P 1=N 2=B 3=R 4=Q 5=K
      var raw = pieceBitboard(board, pc).raw
      while raw != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(raw)
        addColumn(whitePerspIdx(color, ord, sq), acc.white)
        addColumn(blackPerspIdx(color, ord, sq), acc.black)
        raw &= raw - 1L
      pc += 1

  /** Transform `acc` from representing `fromBoard` into representing `toBoard`
    * by ±only the changed feature columns. Symmetric, so the search calls
    * `applyDiff(acc, parent, child)` to make a move and `applyDiff(acc, child,
    * parent)` to unmake it. Robust for every move type (captures, castling, en
    * passant, promotion) because it diffs the raw piece bitboards rather than
    * interpreting the move.
    */
  def applyDiff(
      acc: NnueAccumulator,
      fromBoard: BoardLike,
      toBoard: BoardLike
  ): Unit =
    var pc = 0
    while pc < 12 do
      val color = pc / 6
      val ord = pc % 6
      val fromBB = pieceBitboard(fromBoard, pc).raw
      val toBB = pieceBitboard(toBoard, pc).raw
      // Removed: set in `from`, clear in `to` → subtract those columns.
      var removed = fromBB & ~toBB
      while removed != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(removed)
        subColumn(whitePerspIdx(color, ord, sq), acc.white)
        subColumn(blackPerspIdx(color, ord, sq), acc.black)
        removed &= removed - 1L
      // Added: set in `to`, clear in `from` → add those columns.
      var added = toBB & ~fromBB
      while added != 0L do
        val sq = java.lang.Long.numberOfTrailingZeros(added)
        addColumn(whitePerspIdx(color, ord, sq), acc.white)
        addColumn(blackPerspIdx(color, ord, sq), acc.black)
        added &= added - 1L
      pc += 1

  /** Run the output layer over a maintained accumulator for the given side to
    * move. Picks `stm`/`ntm` perspectives, SCReLUs, dots with the output
    * weights, dequantizes to centipawns, and returns a **white-POV** score
    * (every other [[Evaluator]] consumer expects white-relative).
    */
  def evaluateFrom(acc: NnueAccumulator, sideToMove: Color): Int =
    val whiteToMove = sideToMove == Color.White
    val stm = if whiteToMove then acc.white else acc.black
    val ntm = if whiteToMove then acc.black else acc.white
    // Two independent accumulators (own- and other-perspective) over a
    // single fused loop: halves loop overhead and gives the two reduction
    // chains instruction-level parallelism. Bit-identical to the previous
    // two sequential loops (integer addition is associative).
    var outStm: Long = 0L
    var outNtm: Long = 0L
    var i = 0
    while i < HiddenSize do
      outStm += screlu(stm(i)).toLong * outputWeights(i).toInt
      outNtm += screlu(ntm(i)).toLong * outputWeights(HiddenSize + i).toInt
      i += 1
    val withBias = (outStm + outNtm) / QA + outputBias.toInt
    val cp = (withBias * Scale) / (QA * QB)
    (if whiteToMove then cp else -cp).toInt

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def biasArray(): Array[Int] =
    val a = new Array[Int](HiddenSize)
    resetToBias(a)
    a

  private inline def resetToBias(a: Array[Int]): Unit =
    var i = 0
    while i < HiddenSize do
      a(i) = featureBias(i).toInt
      i += 1

  /** The 12 piece bitboards in (P,N,B,R,Q,K)×(white,black) order — the same
    * order the feature index encodes (`color*384 + ord*64 + sq`).
    */
  private inline def pieceBitboard(b: BoardLike, pc: Int): Bitboard = pc match
    case 0  => b.pawnsW
    case 1  => b.knightsW
    case 2  => b.bishopsW
    case 3  => b.rooksW
    case 4  => b.queensW
    case 5  => b.kingW
    case 6  => b.pawnsB
    case 7  => b.knightsB
    case 8  => b.bishopsB
    case 9  => b.rooksB
    case 10 => b.queensB
    case _  => b.kingB

  /** White's perspective: own (white) pieces in the first 384, no vertical
    * mirror.
    */
  private inline def whitePerspIdx(color: Int, ord: Int, sq: Int): Int =
    color * 384 + ord * 64 + sq

  /** Black's perspective: own (black) pieces in the first 384, board mirrored
    * vertically (`sq ^ 56`) and colour halves swapped.
    */
  private inline def blackPerspIdx(color: Int, ord: Int, sq: Int): Int =
    (1 - color) * 384 + ord * 64 + (sq ^ 56)

  private inline def addColumn(featureIdx: Int, acc: Array[Int]): Unit =
    val base = featureIdx * HiddenSize
    var i = 0
    while i < HiddenSize do
      acc(i) += featureWeights(base + i)
      i += 1

  private inline def subColumn(featureIdx: Int, acc: Array[Int]): Unit =
    val base = featureIdx * HiddenSize
    var i = 0
    while i < HiddenSize do
      acc(i) -= featureWeights(base + i)
      i += 1

  /** Square clipped ReLU — clamp the QA-scale value to `[0, QA]`, then square.
    * Output is at QA² scale.
    */
  private inline def screlu(x: Int): Int =
    val y = Math.max(0, Math.min(x, QA)) // branchless clamp to [0, QA]
    y * y

/** Two turn-independent perspective accumulators (White-POV + Black-POV), each
  * `HiddenSize` ints. Maintained incrementally across make/unmake by
  * [[NnueEvaluator.applyDiff]] and reused (allocate once per search thread, not
  * per eval).
  */
final class NnueAccumulator(val white: Array[Int], val black: Array[Int])

object NnueEvaluator:

  // Hyperparameters — must match the trainer (Bullet `pichess.rs`
  // and the Python `train_nnue.py`).
  final val HiddenSize: Int = 128
  final val InputSize: Int = 768
  final val Scale: Int = 400
  final val QA: Int = 255
  final val QB: Int = 64

  /** Load the network from a classpath resource. Returns None when the resource
    * is absent (engine still works on the HCE eval).
    */
  def loadResource(name: String): Option[NnueEvaluator] =
    Option(getClass.getResourceAsStream(name)).map { res =>
      try
        val bytes = res.readAllBytes()
        parse(bytes)
      finally res.close()
    }

  /** Load the network from a filesystem PATH (not the classpath). Used by the
    * A/B harness to pit a freshly-trained candidate net (e.g.
    * `/tmp/nnue-128-refined.bin`) head-to-head against the baked one without
    * repackaging. Returns None when the file is absent.
    */
  def loadFile(path: String): Option[NnueEvaluator] =
    val f = new java.io.File(path)
    if !f.isFile then None
    else Some(parse(java.nio.file.Files.readAllBytes(f.toPath)))

  /** Parse the raw byte layout into the four weight arrays. The length check
    * rejects mis-sized files before we start reading garbage as quantized
    * weights.
    */
  def parse(bytes: Array[Byte]): NnueEvaluator =
    val expected = InputSize * HiddenSize * 2 + HiddenSize * 2 +
      2 * HiddenSize * 2 + 2
    require(
      bytes.length == expected,
      s"NNUE net size mismatch: got ${bytes.length} bytes, expected $expected"
    )
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val featureWeights = new Array[Int](InputSize * HiddenSize)
    var i = 0
    while i < featureWeights.length do
      featureWeights(i) =
        bb.getShort.toInt // int16 on disk, widened to int32 in memory
      i += 1

    val featureBias = new Array[Short](HiddenSize)
    var b = 0
    while b < HiddenSize do
      featureBias(b) = bb.getShort
      b += 1

    val outputWeights = new Array[Short](2 * HiddenSize)
    var o = 0
    while o < outputWeights.length do
      outputWeights(o) = bb.getShort
      o += 1

    val outputBias = bb.getShort
    new NnueEvaluator(featureWeights, featureBias, outputWeights, outputBias)

package chess.bot.engine.nnue

import java.nio.{ByteBuffer, ByteOrder}

import chess.bot.engine.Evaluator
import chess.model.board.{Bitboard, GameState}
import chess.model.piece.{Color, PieceType}

/** NNUE inference for pichess — perspective net
  * `(768 -> HiddenSize) x 2 -> 1`, int16 quantized.
  *
  * Network shape mirrors what `train_nnue.py` (PyTorch) and
  * `examples/pichess.rs` (Bullet) both emit, so this loader works
  * for either backend. Byte layout (little-endian i16):
  *
  *   1. feature_weights — column-major `HiddenSize × 768`, QA scale.
  *      Stored as 768 accumulator-shaped columns of length HiddenSize.
  *   2. feature_bias    — `HiddenSize` i16s, QA scale.
  *   3. output_weights  — `2 × HiddenSize` i16s, QB scale.
  *   4. output_bias     — one i16, `QA × QB` scale.
  *
  * Evaluation:
  *   * For each side's perspective, build a sparse-feature
  *     accumulator: start at feature_bias, then add one column per
  *     piece on the board (768 indices encode color × type × square,
  *     mirrored vertically for the black perspective).
  *   * Concatenate `(stm_acc, ntm_acc)` and compute
  *     `sum( screlu(acc[i]) * outW[i] ) / QA + outBias`.
  *   * Scale by 400 / (QA × QB) to get centipawns.
  *
  * Returns the score from `state.activeColor`'s POV — flipped to
  * white POV by the caller (`leafEval`) for the rest of the search
  * which expects white-POV scores. */
final class NnueEvaluator private (
    private val featureWeights: Array[Short], // 768 × HiddenSize, column-major
    private val featureBias:    Array[Short], // HiddenSize
    private val outputWeights:  Array[Short], // 2 × HiddenSize
    private val outputBias:     Short,
) extends Evaluator:

  import NnueEvaluator.*

  override def evaluate(state: GameState): Int =
    val stmAcc = newAccumulator()
    val ntmAcc = newAccumulator()
    populate(state, stmAcc, ntmAcc)
    // Output layer: SCReLU each hidden cell, multiply by the
    // matching output weight, accumulate. STM cells take the first
    // HiddenSize output weights, NTM cells take the next batch.
    var out: Long = 0L
    var i = 0
    while i < HiddenSize do
      out += screlu(stmAcc(i)).toLong * outputWeights(i).toInt
      i += 1
    var j = 0
    while j < HiddenSize do
      out += screlu(ntmAcc(j)).toLong * outputWeights(HiddenSize + j).toInt
      j += 1
    // Dequantize: SCReLU produced QA² scale; we already multiplied
    // by an output weight at QB scale. Divide once by QA to bring
    // us back to QA × QB, then add the bias (already at QA × QB).
    val withBias = out / QA + outputBias.toInt
    // Final scale to centipawns. Bullet's reference does
    //   output = withBias × SCALE / (QA × QB)
    val cp = (withBias * Scale) / (QA * QB)
    // Convert from STM POV to white POV — every other consumer of
    // [[Evaluator]] expects white-relative scores.
    val whitePov = if state.activeColor == Color.White then cp else -cp
    whitePov.toInt

  /** Allocate a fresh accumulator initialised to the bias. */
  private def newAccumulator(): Array[Int] =
    val arr = new Array[Int](HiddenSize)
    var i = 0
    while i < HiddenSize do
      arr(i) = featureBias(i).toInt
      i += 1
    arr

  /** Walk every piece on the board, add its feature column to both
    * perspective accumulators. */
  private def populate(state: GameState, stm: Array[Int], ntm: Array[Int]): Unit =
    val board = state.board
    val stmWhite = state.activeColor == Color.White
    addBitboard(board.pawnsW, 0,         PieceType.Pawn,   stmWhite, stm, ntm)
    addBitboard(board.knightsW, 0,       PieceType.Knight, stmWhite, stm, ntm)
    addBitboard(board.bishopsW, 0,       PieceType.Bishop, stmWhite, stm, ntm)
    addBitboard(board.rooksW, 0,         PieceType.Rook,   stmWhite, stm, ntm)
    addBitboard(board.queensW, 0,        PieceType.Queen,  stmWhite, stm, ntm)
    addBitboard(board.kingW, 0,          PieceType.King,   stmWhite, stm, ntm)
    addBitboard(board.pawnsB, 1,         PieceType.Pawn,   stmWhite, stm, ntm)
    addBitboard(board.knightsB, 1,       PieceType.Knight, stmWhite, stm, ntm)
    addBitboard(board.bishopsB, 1,       PieceType.Bishop, stmWhite, stm, ntm)
    addBitboard(board.rooksB, 1,         PieceType.Rook,   stmWhite, stm, ntm)
    addBitboard(board.queensB, 1,        PieceType.Queen,  stmWhite, stm, ntm)
    addBitboard(board.kingB, 1,          PieceType.King,   stmWhite, stm, ntm)

  /** Add every set square in `bb` to the appropriate accumulators.
    * `pieceColor` is 0 for white, 1 for black — used together with
    * `pt` and the square index to compute the perspective-relative
    * feature index. */
  private inline def addBitboard(
      bb: Bitboard,
      pieceColor: Int,
      pt: PieceType,
      stmWhite: Boolean,
      stm: Array[Int],
      ntm: Array[Int],
  ): Unit =
    var raw = bb.raw
    while raw != 0L do
      val sq = java.lang.Long.numberOfTrailingZeros(raw)
      val ptOrd = pieceTypeOrdinal(pt)
      // STM index: from side-to-move's POV. Black-to-move mirrors
      // vertically (sq ^ 56) and swaps colour halves.
      val (stmIdx, ntmIdx) =
        if stmWhite then
          (pieceColor * 384 + ptOrd * 64 + sq,
           (1 - pieceColor) * 384 + ptOrd * 64 + (sq ^ 56))
        else
          ((1 - pieceColor) * 384 + ptOrd * 64 + (sq ^ 56),
           pieceColor * 384 + ptOrd * 64 + sq)
      addColumn(stmIdx, stm)
      addColumn(ntmIdx, ntm)
      raw &= raw - 1L

  /** Add one feature column (one piece on one square from one
    * perspective) to its accumulator. */
  private inline def addColumn(featureIdx: Int, acc: Array[Int]): Unit =
    val base = featureIdx * HiddenSize
    var i = 0
    while i < HiddenSize do
      acc(i) += featureWeights(base + i).toInt
      i += 1

  /** Square clipped ReLU — match Bullet's reference: clamp the
    * QA-scale accumulator value to `[0, QA]`, then square. Output
    * is at QA² scale. */
  private inline def screlu(x: Int): Int =
    val y = if x < 0 then 0 else if x > QA then QA else x
    y * y

  private inline def pieceTypeOrdinal(pt: PieceType): Int = pt match
    case PieceType.Pawn   => 0
    case PieceType.Knight => 1
    case PieceType.Bishop => 2
    case PieceType.Rook   => 3
    case PieceType.Queen  => 4
    case PieceType.King   => 5

object NnueEvaluator:

  // Hyperparameters — must match the trainer (Bullet `pichess.rs`
  // and the Python `train_nnue.py`).
  final val HiddenSize: Int = 128
  final val InputSize:  Int = 768
  final val Scale:      Int = 400
  final val QA:         Int = 255
  final val QB:         Int = 64

  /** Load the network from a classpath resource. Returns None when
    * the resource is absent (engine still works on the HCE eval). */
  def loadResource(name: String): Option[NnueEvaluator] =
    Option(getClass.getResourceAsStream(name)).map { res =>
      try
        val bytes = res.readAllBytes()
        parse(bytes)
      finally res.close()
    }

  /** Parse the raw byte layout into the four weight arrays. The
    * length check rejects mis-sized files before we start reading
    * garbage as quantized weights. */
  def parse(bytes: Array[Byte]): NnueEvaluator =
    val expected = InputSize * HiddenSize * 2 + HiddenSize * 2 +
      2 * HiddenSize * 2 + 2
    require(
      bytes.length == expected,
      s"NNUE net size mismatch: got ${bytes.length} bytes, expected $expected",
    )
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val featureWeights = new Array[Short](InputSize * HiddenSize)
    var i = 0
    while i < featureWeights.length do
      featureWeights(i) = bb.getShort
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

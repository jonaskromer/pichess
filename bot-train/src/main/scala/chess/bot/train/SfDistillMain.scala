package chess.bot.train

import java.nio.file.Paths

import zio.*

import chess.bot.engine.{TaperedFeatureExtractor, WeightSnapshot, WeightsLoader}
import chess.codec.FenParserRegex

/** Stockfish-eval distillation for the HCE weights (roadmap 7b).
  *
  * Re-tunes the hand-crafted evaluator by fitting it to **Stockfish's evals**
  * (the shared Lichess-eval dataset's `cp` column) instead of game outcomes —
  * the same strong teacher that powered the NNUE. It reuses the proven
  * [[TexelTuner]] unchanged; the *only* difference from outcome-tuning is the
  * per-sample target, which becomes `sigmoid(K · SF_stm_cp)` — so the tuner
  * (which predicts `sigmoid(K · HCE_cp)`) drives `HCE_cp` toward `SF_cp`. The
  * row's search depth is the confidence weight, optionally scaled up for sparse
  * endgame positions (`ENDGAME_BOOST`) so the tuner fits the `_eg` half of the
  * tapered weights harder — mirroring the NNUE trainer's `--endgame-boost` so
  * both halves of the hybrid sharpen the same (local, tablebase-free) endgames.
  *
  * The HCE is a low-capacity **linear** model (a few hundred weights), so a
  * representative SUBSAMPLE of the 342M-position dataset is statistically
  * ample (`--max-samples`, default 3M). (The NNUE retrain, by contrast, wants
  * all of it.) `tune` streams the samples into a compact corpus, so only the
  * collected light rows (fen + target + weight) sit in memory, not the
  * feature maps.
  *
  * Run:  sbt 'botTrain/runMain chess.bot.train.SfDistillMain [tsv] [outPath]'
  * Env:  PICHESS_SFDISTILL_{TSV,MAXSAMPLES,K,INITV,OUTV,STRIDE,MAXITERS,
  *       ENDGAME_PIECES,ENDGAME_BOOST}
  */
object SfDistillMain extends ZIOAppDefault:

  private val Extractor = TaperedFeatureExtractor.full
  private val ClampCp   = 1000 // drop mate-sentinel outliers before the sigmoid

  /** SF distillation target: White-POV cp → side-to-move POV → clamp →
    * through the tuner's own sigmoid (same `K`), so fitting it aligns
    * `HCE_cp` with `SF_cp`. */
  def targetOutcome(whiteCp: Int, whiteToMove: Boolean, k: Double): Double =
    val stm     = if whiteToMove then whiteCp else -whiteCp
    val clamped = math.max(-ClampCp, math.min(ClampCp, stm))
    TexelTuner.sigmoid(k * clamped)

  /** Search depth → confidence weight in (0, 1], saturating (a depth-45 eval
    * isn't twice as trustworthy as a depth-24 one). */
  def confidence(depth: Int): Double = math.min(1.0, depth.toDouble / 24.0)

  /** Total pieces on the board (the FEN's board field). */
  def pieceCount(fen: String): Int = fen.takeWhile(_ != ' ').count(_.isLetter)

  /** Per-sample weight: depth confidence × an endgame-emphasis multiplier.
    * Positions with ≤ `endgamePieces` pieces are up-weighted by `endgameBoost`,
    * so the tuner fits the `_eg` (endgame) weights — which dominate at phase ≈
    * 0 — harder. `endgameBoost = 1.0` ⇒ off (plain depth weight). */
  def sampleWeight(fen: String, depth: Int, endgamePieces: Int, endgameBoost: Double): Double =
    val base = confidence(depth)
    if endgameBoost != 1.0 && pieceCount(fen) <= endgamePieces then base * endgameBoost
    else base

  private def whiteToMove(fen: String): Boolean = fen.contains(" w ")

  private def parseState(fen: String) =
    zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(FenParserRegex.parse(fen).either).getOrThrow().toOption
    }

  /** A collected light row: fen + precomputed target + weight. Features are
    * (re)built lazily during tuning so the feature maps never all coexist. */
  private[train] final case class DistillRow(fen: String, outcome: Double, weight: Double)

  private[train] def toSample(dr: DistillRow): Option[TexelTuner.Sample] =
    parseState(dr.fen).map(st => TexelTuner.Sample(Extractor.features(st), dr.outcome, dr.weight))

  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args   <- getArgs
      tsv     = args.headOption.getOrElse(sys.env.getOrElse("PICHESS_SFDISTILL_TSV", "nnue-train/data/lichess-eval.tsv.gz"))
      outP    = args.lift(1).getOrElse("bot-engine/src/main/resources/weights/v9.json")
      maxS    = intEnv("PICHESS_SFDISTILL_MAXSAMPLES", 3_000_000)
      k       = doubleEnv("PICHESS_SFDISTILL_K", 0.25)
      initV   = intEnv("PICHESS_SFDISTILL_INITV", 8)
      outV    = intEnv("PICHESS_SFDISTILL_OUTV", 9)
      stride  = math.max(1, intEnv("PICHESS_SFDISTILL_STRIDE", 1))
      iters    = intEnv("PICHESS_SFDISTILL_MAXITERS", 60)
      egPieces = intEnv("PICHESS_SFDISTILL_ENDGAME_PIECES", 7)
      egBoost  = doubleEnv("PICHESS_SFDISTILL_ENDGAME_BOOST", 1.0)
      initial <- WeightsLoader.load(initV).mapError(e => new RuntimeException(e.toString)).map(_.weights)
      rows <- LichessEvalReader
                .stream(Paths.get(tsv))
                .zipWithIndex
                .collect {
                  case (r, i) if i % stride == 0 =>
                    DistillRow(
                      r.fen,
                      targetOutcome(r.whiteCp(), whiteToMove(r.fen), k),
                      sampleWeight(r.fen, r.depth, egPieces, egBoost),
                    )
                }
                .take(maxS.toLong)
                .runCollect
      _       <- Console.printLine(s"SF-distill: ${rows.size} samples, init v$initV, K=$k, $iters iters")
      samples  = () => rows.iterator.flatMap(toSample)
      before  <- ZIO.attemptBlocking(TexelTuner.totalLoss(samples(), initial, k))
      tuned   <- ZIO.attemptBlocking(TexelTuner.tune(samples(), initial, k, iters))
      _       <- WeightsLoader.writeFile(WeightSnapshot(outV, tuned.weights), Paths.get(outP))
      _       <- Console.printLine(f"loss $before%.5f -> ${tuned.finalLoss}%.5f (${tuned.iterations} iters) -> $outP")
    yield ()

  private def intEnv(n: String, d: Int): Int       = sys.env.get(n).flatMap(_.toIntOption).getOrElse(d)
  private def doubleEnv(n: String, d: Double): Double = sys.env.get(n).flatMap(_.toDoubleOption).getOrElse(d)

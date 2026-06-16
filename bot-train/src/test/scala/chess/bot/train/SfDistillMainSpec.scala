package chess.bot.train

import zio.*
import zio.test.*

import chess.bot.engine.WeightsLoader

object SfDistillMainSpec extends ZIOSpecDefault:

  private val K = 0.25

  def spec = suite("SfDistillMain")(
    test("targetOutcome maps SF cp to a side-to-move win-prob") {
      assertTrue(
        SfDistillMain.targetOutcome(900, whiteToMove = true, K) > 0.99,  // white winning, white to move → ~win
        SfDistillMain.targetOutcome(900, whiteToMove = false, K) < 0.01, // same position, black to move → ~loss
        math.abs(SfDistillMain.targetOutcome(0, whiteToMove = true, K) - 0.5) < 1e-9,
      )
    },
    test("confidence saturates at depth 24") {
      assertTrue(
        SfDistillMain.confidence(24) == 1.0,
        SfDistillMain.confidence(12) == 0.5,
        SfDistillMain.confidence(48) == 1.0,
      )
    },
    test("endgame emphasis up-weights ≤7-piece positions (and only when boost>1)") {
      val midgame = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" // 32 pieces
      val endgame = "4k3/8/8/8/8/8/8/4R1K1 w - - 0 1"                          // 3 pieces (KR vs K)
      assertTrue(
        SfDistillMain.pieceCount(midgame) == 32,
        SfDistillMain.pieceCount(endgame) == 3,
        // depth 24 → confidence 1.0; endgame ×6, midgame unchanged
        SfDistillMain.sampleWeight(endgame, 24, endgamePieces = 7, endgameBoost = 6.0) == 6.0,
        SfDistillMain.sampleWeight(midgame, 24, endgamePieces = 7, endgameBoost = 6.0) == 1.0,
        // boost off (1.0) → just the depth confidence, even for the endgame
        SfDistillMain.sampleWeight(endgame, 24, endgamePieces = 7, endgameBoost = 1.0) == 1.0,
      )
    },
    test("distillation runs end-to-end (FEN → features → SF target → tune) and never worsens loss") {
      val rows = List(
        // white up a queen → SF ≈ +900 → target ≈ win
        SfDistillMain.DistillRow("4k3/8/8/8/8/8/8/3QK3 w - - 0 1", SfDistillMain.targetOutcome(900, true, K), 1.0),
        // black up a queen → SF ≈ −900 → target ≈ loss
        SfDistillMain.DistillRow("3qk3/8/8/8/8/8/8/4K3 w - - 0 1", SfDistillMain.targetOutcome(-900, true, K), 1.0),
        // balanced start → ≈ 0
        SfDistillMain.DistillRow(
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
          SfDistillMain.targetOutcome(20, true, K),
          1.0,
        ),
      )
      for
        initial <- WeightsLoader.load(8).mapError(e => new RuntimeException(e.toString)).map(_.weights)
        samples  = () => rows.iterator.flatMap(SfDistillMain.toSample)
        before   = TexelTuner.totalLoss(samples(), initial, K)
        tuned    = TexelTuner.tune(samples(), initial, K, maxIterations = 40)
      yield assertTrue(
        tuned.finalLoss <= before + 1e-9, // coordinate descent never increases loss
        tuned.weights.nonEmpty,
      )
    },
    // regression: the Lichess-eval dataset FENs are 4-field (board, stm,
    // castling, ep) with NO halfmove/fullmove counters, but FenParserRegex
    // requires all 6 fields. Before the fix `toSample` returned None for every
    // real row, so the corpus was empty and `tune` silently returned the init
    // weights unchanged (loss 0.0, 0 iters) — the "distilled" net was just the
    // input relabeled. `SfDistillMain.normalizeFen` pads the missing counters.
    test("regression: toSample parses 4-field Lichess FENs (no move counters)") {
      val fourField = "7r/1p3k2/p1bPR3/5p2/2B2P1p/8/PP4P1/3K4 b - -" // real shard FEN
      val sample = SfDistillMain.toSample(SfDistillMain.DistillRow(fourField, 0.5, 1.0))
      assertTrue(sample.isDefined, sample.exists(_.features.nonEmpty))
    },
  )

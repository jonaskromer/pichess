package chess.bot.engine

import chess.model.board.PositionView

/** Material-only evaluator. See [[Evaluator.materialOnly]] for context.
  *
  * Counted directly from the 12 per-piece bitboards via `popCount` — no
  * iteration, ~12 bitcount instructions per call. Centipawn values are the
  * Kaufman / starter-engine standard set.
  */
private object MaterialEvaluator extends Evaluator:

  private inline val PawnCp = 100
  private inline val KnightCp = 320
  private inline val BishopCp = 330
  private inline val RookCp = 500
  private inline val QueenCp = 900

  def evaluate(state: PositionView): Int =
    val b = state.board
    val whiteMaterial =
      b.pawnsW.popCount * PawnCp +
        b.knightsW.popCount * KnightCp +
        b.bishopsW.popCount * BishopCp +
        b.rooksW.popCount * RookCp +
        b.queensW.popCount * QueenCp
    val blackMaterial =
      b.pawnsB.popCount * PawnCp +
        b.knightsB.popCount * KnightCp +
        b.bishopsB.popCount * BishopCp +
        b.rooksB.popCount * RookCp +
        b.queensB.popCount * QueenCp
    whiteMaterial - blackMaterial

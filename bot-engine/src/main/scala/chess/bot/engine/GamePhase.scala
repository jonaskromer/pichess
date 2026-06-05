package chess.bot.engine

import chess.model.board.{BoardState, GameState}

/** Single-number summary of how far into the endgame a position is.
  *
  * Returns a value in [0.0, 1.0]:
  *   - **1.0** — full opening / starting material (24 phase units)
  *   - **0.0** — bare kings + pawns (no minor or major pieces left)
  *
  * The Texel-tuner / tapered-eval consumers use this to blend two
  * weight sets: `score = phase * opening_eval + (1 - phase) * endgame_eval`.
  *
  * Phase values per piece (Stockfish convention, slightly trimmed):
  *
  * {{{
  *   Queen   = 4
  *   Rook    = 2
  *   Bishop  = 1
  *   Knight  = 1
  *   Pawn    = 0  (pawns barely affect phase — they're equally present in
  *                 opening and endgame; the relevant distinction is whether
  *                 minor / major pieces are still on the board)
  * }}}
  *
  * Maximum is 2 sides × (4 + 2·2 + 2·1 + 2·1) = **24**.
  *
  * The clamp at 1.0 protects the rare case where a side has been
  * promoting (more queens than the starting 1) — that briefly pushes
  * raw phase above 24, which we cap.
  */
object GamePhase:

  private inline val QueenPhase  = 4
  private inline val RookPhase   = 2
  private inline val BishopPhase = 1
  private inline val KnightPhase = 1
  private inline val MaxPhase    = 24

  def compute(state: GameState): Double = compute(state.board)

  def compute(board: BoardState): Double =
    val raw =
      (board.queensW.popCount  + board.queensB.popCount ) * QueenPhase  +
      (board.rooksW.popCount   + board.rooksB.popCount  ) * RookPhase   +
      (board.bishopsW.popCount + board.bishopsB.popCount) * BishopPhase +
      (board.knightsW.popCount + board.knightsB.popCount) * KnightPhase
    math.min(raw, MaxPhase).toDouble / MaxPhase.toDouble

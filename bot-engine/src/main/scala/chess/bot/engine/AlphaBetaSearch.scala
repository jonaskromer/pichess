package chess.bot.engine

import zio.{UIO, ZIO}

import chess.bot.engine.internal.RulesAdapter
import chess.model.board.{GameState, Move}
import chess.model.piece.Color
import chess.model.rules.Zobrist

/** Fixed-depth negamax α-β with TT support. Used via the public
  * factory [[Search.alphaBeta]].
  *
  * Score convention everywhere in this file is from the **side-to-move**
  * perspective (negamax). The evaluator returns white-POV though, so
  * leaf scores get negated on black-to-move before bubbling up.
  *
  * Search returns `None` only when the root position has zero legal
  * moves. Internally, terminal positions deeper down score as
  * `-MateScore + ply` (the side to move has been checkmated) or `0`
  * (stalemate, a draw).
  */
private[engine] final class AlphaBetaSearch(
    eval: Evaluator,
    tt: TranspositionTable,
    book: OpeningBook,
) extends Search:

  import Search.{Infinity, MateScore}
  import TranspositionTable.{Entry, Kind}

  def bestMove(state: GameState, depth: Int): UIO[Option[Move]] =
    // Book lookup short-circuits search when the position is known —
    // returning Some(bookMove) skips the α-β work entirely. On miss
    // (or book exhausted) we fall through to native search.
    book.lookup(state).flatMap {
      case Some(move) => ZIO.some(move)
      case None       => ZIO.succeed(syncBestMove(state, depth))
    }

  /** Pick the move at the root that maximises the negamax score for
    * the side to move. Mirrors the negamax recursion below but tracks
    * the *move* (not just the score) so we can return it. */
  private def syncBestMove(state: GameState, depth: Int): Option[Move] =
    val moves = RulesAdapter.legalMoves(state)
    if moves.isEmpty then None
    else
      val ordered = orderMoves(moves, Zobrist.hash(state))
      var alpha = -Infinity
      val beta  = Infinity
      // Seed `best` with the first move so we always return a move when
      // there's at least one legal option — even if every option scores
      // identically (pathological "all lose by mate" case, or all-tied
      // material positions where the eval flatlines).
      var best: Option[Move] = Some(ordered.head)
      var bestScore = -Infinity
      val it = ordered.iterator
      while it.hasNext do
        val move = it.next()
        RulesAdapter.applyMove(state, move).foreach { next =>
          val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1)
          if score > bestScore then
            bestScore = score
            best = Some(move)
          if score > alpha then alpha = score
        }
      best

  /** Negamax core.
    *
    * Returns the side-to-move score of `state` under an α-β window. On
    * TT hit at sufficient depth the stored score is reused directly
    * (subject to bound tightness vs the current window). On miss the
    * search recurses, then writes the result back keyed by Zobrist.
    *
    * Termination cases:
    *   - depth ≤ 0           → static eval (leaf)
    *   - no legal moves +
    *     side-to-move in check → mate, score `-(MateScore - ply)`
    *   - no legal moves +
    *     side-to-move safe    → stalemate, score 0
    */
  private def negamax(
      state: GameState,
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
  ): Int =
    val hash = Zobrist.hash(state)
    probeTt(hash, depth, alpha, beta) match
      case Some(score) => score
      case None =>
        if depth <= 0 then leafEval(state)
        else
          val moves = RulesAdapter.legalMoves(state)
          if moves.isEmpty then terminalScore(state, ply)
          else searchMoves(state, moves, depth, alpha, beta, ply, hash)

  /** Static evaluation at a leaf node, normalised to side-to-move POV.
    * The Evaluator hands back white-POV centipawns; flip on black. */
  private def leafEval(state: GameState): Int =
    val raw = eval.evaluate(state)
    if state.activeColor == Color.White then raw else -raw

  /** Score for "the side to move has no legal moves". In-check → mate
    * (scaled by `ply` so shorter mates beat longer ones); not in check
    * → stalemate (draw). */
  private def terminalScore(state: GameState, ply: Int): Int =
    if RulesAdapter.isInCheck(state) then -(MateScore - ply)
    else 0

  /** Iterate candidates with α-β cutoff, write the result to the TT,
    * return the best score. */
  private def searchMoves(
      state: GameState,
      moves: List[Move],
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
      hash: Long,
  ): Int =
    val ordered = orderMoves(moves, hash)
    var alphaCur  = alpha
    var bestScore = -Infinity
    var bestMove: Option[Move] = None
    var cutoff = false
    val it = ordered.iterator
    while it.hasNext && !cutoff do
      val move = it.next()
      RulesAdapter.applyMove(state, move).foreach { next =>
        val score = -negamax(next, depth - 1, -beta, -alphaCur, ply + 1)
        if score > bestScore then
          bestScore = score
          bestMove = Some(move)
        if score > alphaCur then alphaCur = score
        if alphaCur >= beta then cutoff = true
      }
    val kind =
      if bestScore <= alpha then Kind.Upper
      else if bestScore >= beta then Kind.Lower
      else Kind.Exact
    tt.put(hash, Entry(depth, bestScore, kind, bestMove))
    bestScore

  /** Move ordering: try the TT's best move first, then the rest in
    * generation order. A correct first move triggers a β-cutoff at the
    * root, which collapses the entire remainder of the search.
    *
    * Phase 1 has no other ordering heuristics — MVV-LVA, killers, and
    * history come in once the engine plays full games on Lichess and
    * we have data to drive them. */
  private def orderMoves(moves: List[Move], hash: Long): List[Move] =
    tt.get(hash).flatMap(_.bestMove) match
      case Some(ttMove) if moves.contains(ttMove) =>
        ttMove :: moves.filterNot(_ == ttMove)
      case _ => moves

  /** Read the TT entry for `hash` (if any) and decide whether it
    * settles the current α/β query.
    *
    * - Exact and depth ≥ requested → return stored score.
    * - Lower bound ≥ β             → cutoff: stored is already too good.
    * - Upper bound ≤ α             → cutoff: stored caps the value below α.
    *
    * Other cases (e.g. an Upper bound between α and β) leave the
    * caller to re-search; the TT entry's bestMove will still be used
    * for ordering.
    */
  private def probeTt(hash: Long, depth: Int, alpha: Int, beta: Int): Option[Int] =
    tt.get(hash) match
      case Some(entry) if entry.depth >= depth =>
        entry.kind match
          case Kind.Exact                            => Some(entry.score)
          case Kind.Lower if entry.score >= beta     => Some(entry.score)
          case Kind.Upper if entry.score <= alpha    => Some(entry.score)
          case _                                     => None
      case _ => None

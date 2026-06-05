package chess.bot.engine.internal

import zio.{Runtime, Unsafe}

import chess.model.board.{GameState, Move, MoveInt}
import chess.model.piece.PieceType
import chess.model.rules.{Game, MoveValidator}

/** Sync bridge over the IO-typed rules engine.
  *
  * The search recurses thousands of times per top-level call; threading
  * ZIO's effect system through every recursion would burn measurable
  * scheduling overhead for no benefit (the work itself is pure CPU,
  * fully sync). This adapter unsafe-runs the IO at the boundary and
  * hands back plain values so the search loop can stay inside one
  * synchronous frame.
  *
  * The unsafe is encapsulated here so the rest of the engine module
  * never sees `Unsafe.unsafe { … }` — search and eval code stays in the
  * value world. The public engine surface (`Search.bestMove`) keeps a
  * ZIO type so consumers don't pay this complexity either.
  */
private[engine] object RulesAdapter:

  private val runtime = Runtime.default

  /** Enumerate every legal move from `state` for the side to move.
    *
    * Promotion behaviour: `MoveValidator.legalDestinationsIndex`
    * collapses the four promotion targets to one destination per
    * pawn-on-back-rank move. Phase 1 defaults the promotion choice to
    * Queen — that's the optimal pick in the overwhelming majority of
    * positions. Under-promotions (knight forks etc.) become a Phase ≥ 2
    * concern once the engine is otherwise strong enough to notice.
    */
  def legalMoves(state: GameState): List[Move] =
    val index = Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(MoveValidator.legalDestinationsIndex(state))
        .getOrThrow()
    }
    val buf = scala.collection.mutable.ListBuffer.empty[Move]
    val it = index.iterator
    while it.hasNext do
      val (from, destinations) = it.next()
      val piece = state.board.get(from)
      val isPawn = piece.exists(_.pieceType == PieceType.Pawn)
      destinations.foreach { to =>
        val promotion =
          if isPawn && (to.row == 1 || to.row == 8) then Some(PieceType.Queen)
          else None
        buf += Move(from, to, promotion)
      }
    buf.toList

  /** Apply `move` to `state` returning the post-move state on success
    * or `None` if the move was illegal. Defect-grade failures
    * (`GameError.InfrastructureError`) propagate as exceptions — those
    * aren't expected on a candidate move from `legalMoves`.
    */
  def applyMove(state: GameState, move: Move): Option[GameState] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(Game.applyMove(state, move).either)
        .getOrThrow()
        .toOption
    }

  /** Side-to-move-in-check predicate. Pure delegate to
    * [[MoveValidator.isInCheck]] (which is already sync). Lives here so
    * the engine has a single import for the rules surface.
    */
  def isInCheck(state: GameState): Boolean =
    MoveValidator.isInCheck(state.board, state.activeColor)

  /** Hot-path variant of [[legalMoves]]: writes packed-Int moves
    * into `out` (see [[MoveInt]] for the encoding) and returns the
    * number of moves written. No `Move` / `Option` / `List`
    * allocations on the move side — only the underlying
    * `Map[Position, List[Position]]` from the rules layer is
    * allocated as today.
    *
    * `out` must be sized ≥ 256 (an upper bound on the number of
    * legal moves from any chess position; in practice ≤ 218).
    * Caller pre-allocates one buffer per ply so the recursion is
    * zero-alloc on the move-list path.
    *
    * Same promotion convention as [[legalMoves]] — Phase 1 always
    * promotes to Queen; under-promotions come in a later phase. */
  def fillLegalMoves(state: GameState, out: Array[Int]): Int =
    val index = Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(MoveValidator.legalDestinationsIndex(state))
        .getOrThrow()
    }
    var n = 0
    val it = index.iterator
    while it.hasNext do
      val (from, destinations) = it.next()
      val piece = state.board.get(from)
      val isPawn = piece.exists(_.pieceType == PieceType.Pawn)
      val fromIdx = from.squareIdx
      val destIt = destinations.iterator
      while destIt.hasNext do
        val to = destIt.next()
        val toIdx = to.squareIdx
        val promo =
          if isPawn && (to.row == 1 || to.row == 8) then MoveInt.PromoQueen
          else MoveInt.NoPromotion
        out(n) = MoveInt.encode(fromIdx, toIdx, promo)
        n += 1
    n

  /** Int-based apply: decode the packed move into a [[Move]] case
    * class (one allocation per call, unavoidable until the rules
    * layer accepts primitives directly) and run the existing
    * `Game.applyMove`. The Move case class will fall out of the
    * hot path once the rules layer's apply gets a primitive
    * variant. */
  def applyMoveInt(state: GameState, moveInt: Int): Option[GameState] =
    applyMove(state, MoveInt.decode(moveInt))

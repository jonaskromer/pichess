package chess.bot.engine.internal

import zio.{Runtime, Unsafe}

import chess.model.board.{GameState, Move}
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

package chess.model

import chess.model.board.{GameState, Move}
import chess.model.piece.Color
import chess.model.rules.Zobrist
import chess.notation.SanSerializer
import zio.{IO, ZIO}

/** One played move in the game's history: the [[Move]] itself, the
  * [[GameState]] it produced, the color of the side that played it
  * (the pre-move active color), and its SAN string.
  *
  * Storing all four together replaces the previous parallel
  * `history: List[(Move, GameState)]` + `moveLog: List[(Color, String)]`
  * representation. Keeping them in one list makes the desynced-Nil
  * cases (where `history` and `moveLog` had different lengths)
  * structurally impossible — neither [[GameSnapshot.undoOnce]] nor
  * [[GameSnapshot.redoOnce]] needs a defensive branch for that
  * anymore.
  */
final case class HistoryEntry(
    move: Move,
    state: GameState,
    preColor: Color,
    san: String
)

/** Immutable snapshot of a game in progress: the gameId, the initial position,
  * the move history, and the redo stack.
  *
  * `positionCounts` tracks how many times each Zobrist-hashed position has been
  * reached. It is maintained incrementally by the instance helpers
  * ([[recordMove]], [[undoOnce]], [[redoOnce]]) so repetition detection is O(1)
  * per query rather than O(history length). Callers should construct snapshots
  * via [[GameSnapshot.fresh]] or [[GameSnapshot.fromHistory]] — the case-class
  * default of `Map.empty` is only suitable for internal `.copy()` chaining
  * inside the helpers themselves.
  *
  * [[history]] is stored newest-first (head = most recent move), matching
  * the previous representation. [[redoStack]] is also newest-first (head =
  * the move most recently undone, ready to redo). The [[moveLog]] and
  * [[redoMoveLog]] accessors project the chronological `(Color, San)`
  * view that [[chess.gameservice.GrpcMappers.toStateReply]] consumes.
  */
case class GameSnapshot(
    gameId: GameId,
    initialState: GameState,
    history: List[HistoryEntry],
    redoStack: List[HistoryEntry],
    positionCounts: Map[Long, Int],
):
  def state: GameState = history.headOption.map(_.state).getOrElse(initialState)
  def moves: List[Move] = history.reverse.map(_.move)

  /** Chronological SAN log (oldest first). Derived from [[history]] — no
    * separate field, so it can't drift out of sync.
    */
  def moveLog: List[(Color, String)] =
    history.reverse.map(e => (e.preColor, e.san))

  /** Bare `(Move, GameState)` projection of [[history]] for consumers
    * that don't need the per-entry SAN/Color (e.g.
    * [[chess.notation.SanSerializer.deriveMoveLog]]).
    */
  def historyMoves: List[(Move, GameState)] =
    history.map(e => (e.move, e.state))

  /** Advance history by one move, updating positionCounts and clearing redo.
    * The new entry pairs the move + resulting state with the
    * *pre-move* active color and its SAN string.
    */
  def recordMove(move: Move, newState: GameState, san: String): GameSnapshot =
    val key      = Zobrist.hash(newState)
    val preColor = state.activeColor
    copy(
      history = HistoryEntry(move, newState, preColor, san) :: history,
      redoStack = Nil,
      positionCounts =
        positionCounts.updatedWith(key)(_.map(_ + 1).orElse(Some(1))),
    )

  /** Pop the top of history onto redoStack. `None` when there is nothing to
    * undo.
    */
  def undoOnce: Option[GameSnapshot] = history match
    case Nil => None
    case top :: rest =>
      val key = Zobrist.hash(top.state)
      Some(
        copy(
          history = rest,
          redoStack = top :: redoStack,
          positionCounts = positionCounts.updatedWith(key) {
            case Some(n) if n > 1 => Some(n - 1)
            case _                => None
          },
        )
      )

  /** Push the top of redoStack back onto history. `None` when empty. */
  def redoOnce: Option[GameSnapshot] = redoStack match
    case Nil => None
    case top :: rest =>
      val key = Zobrist.hash(top.state)
      Some(
        copy(
          history = top :: history,
          redoStack = rest,
          positionCounts =
            positionCounts.updatedWith(key)(_.map(_ + 1).orElse(Some(1))),
        )
      )

  /** Replace the state at the top of history without changing the move or the
    * redo stack. Used when [[chess.controller.GameController.claimDraw]]
    * promotes the current position to a Draw status, or when fivefold detection
    * promotes to auto-Draw. Zobrist hashes `status`-identical states equally,
    * so no position-count update is needed.
    */
  def replaceHead(newState: GameState): GameSnapshot =
    history match
      case Nil          => this
      case top :: rest  => copy(history = top.copy(state = newState) :: rest)

  /** Update the snapshot's *current* state, regardless of whether history is
    * empty. When history is non-empty this is equivalent to [[replaceHead]];
    * when history is empty (no moves played yet) it updates [[initialState]]
    * directly so [[state]] still resolves to `newState`.
    *
    * Used by terminal transitions that can fire before any move has been made —
    * e.g. forfeit at move 0.
    */
  def withCurrentState(newState: GameState): GameSnapshot =
    history match
      case Nil => copy(initialState = newState)
      case _   => replaceHead(newState)

  /** How many times the given state's position has occurred in this game. */
  def countOf(state: GameState): Int =
    positionCounts.getOrElse(Zobrist.hash(state), 0)

object GameSnapshot:
  /** Construct a fresh snapshot from the starting position. */
  def fresh(gameId: GameId, initialState: GameState): GameSnapshot =
    GameSnapshot(
      gameId = gameId,
      initialState = initialState,
      history = Nil,
      redoStack = Nil,
      positionCounts = Map(Zobrist.hash(initialState) -> 1)
    )

  /** Construct a snapshot from a loaded history (e.g. PGN replay). The
    * positionCounts map is derived by folding Zobrist.hash across the initial
    * state and every historical state. SAN strings + pre-move colors are
    * derived in a single pass via
    * [[chess.notation.SanSerializer.deriveMoveLog]] so the resulting
    * snapshot has the complete [[HistoryEntry]] per move without any
    * later derivation cost.
    */
  def fromHistory(
      gameId: GameId,
      initialState: GameState,
      history: List[(Move, GameState)]
  ): IO[GameError, GameSnapshot] =
    val allStates = initialState :: history.map(_._2)
    val counts = allStates.foldLeft(Map.empty[Long, Int]) { (acc, s) =>
      val k = Zobrist.hash(s)
      acc.updatedWith(k)(_.map(_ + 1).orElse(Some(1)))
    }
    SanSerializer
      .deriveMoveLog(initialState, history)
      .map { log =>
        // `log` is chronological (oldest first); `history` is also
        // chronological as it comes in. Zip them then reverse so the
        // resulting list is newest-first, matching the storage convention.
        val entries = history
          .zip(log)
          .map { case ((move, state), (preColor, san)) =>
            HistoryEntry(move, state, preColor, san)
          }
          .reverse
        GameSnapshot(
          gameId,
          initialState,
          entries,
          Nil,
          counts,
        )
      }

case class SessionState(
    game: GameSnapshot,
    error: Option[String] = None,
    output: Option[String] = None
):
  export game.{
    gameId,
    initialState,
    history,
    historyMoves,
    redoStack,
    state,
    moves,
  }

package chess.model

import chess.model.board.{GameState, Move}
import chess.model.rules.Zobrist

/** Immutable snapshot of a game in progress: the gameId, the initial position,
  * the move history, and the redo stack.
  *
  * `positionCounts` tracks how many times each Zobrist-hashed position has
  * been reached. It is maintained incrementally by the instance helpers
  * ([[recordMove]], [[undoOnce]], [[redoOnce]]) so repetition detection is
  * O(1) per query rather than O(history length). Callers should construct
  * snapshots via [[GameSnapshot.fresh]] or [[GameSnapshot.fromHistory]] —
  * the case-class default of `Map.empty` is only suitable for internal
  * `.copy()` chaining inside the helpers themselves.
  */
case class GameSnapshot(
    gameId: GameId,
    initialState: GameState,
    history: List[(Move, GameState)],
    redoStack: List[(Move, GameState)],
    positionCounts: Map[Long, Int]
):
  def state: GameState = history.headOption.map(_._2).getOrElse(initialState)
  def moves: List[Move] = history.reverse.map(_._1)

  /** Advance history by one move, updating positionCounts and clearing redo. */
  def recordMove(move: Move, newState: GameState): GameSnapshot =
    val key = Zobrist.hash(newState)
    copy(
      history = (move, newState) :: history,
      redoStack = Nil,
      positionCounts =
        positionCounts.updatedWith(key)(_.map(_ + 1).orElse(Some(1)))
    )

  /** Pop the top of history onto redoStack. `None` when there is nothing to
    * undo, so the caller decides how to surface that to the user.
    */
  def undoOnce: Option[GameSnapshot] = history match
    case Nil => None
    case (move, state) :: rest =>
      val key = Zobrist.hash(state)
      Some(
        copy(
          history = rest,
          redoStack = (move, state) :: redoStack,
          positionCounts = positionCounts.updatedWith(key) {
            case Some(n) if n > 1 => Some(n - 1)
            case _                => None
          }
        )
      )

  /** Push the top of redoStack back onto history. `None` when empty. */
  def redoOnce: Option[GameSnapshot] = redoStack match
    case Nil => None
    case (move, state) :: rest =>
      val key = Zobrist.hash(state)
      Some(
        copy(
          history = (move, state) :: history,
          redoStack = rest,
          positionCounts =
            positionCounts.updatedWith(key)(_.map(_ + 1).orElse(Some(1)))
        )
      )

  /** Replace the state at the top of history without changing the move or the
    * redo stack. Used when [[chess.controller.GameController.claimDraw]]
    * promotes the current position to a Draw status, or when fivefold
    * detection promotes to auto-Draw. Zobrist hashes `status`-identical
    * states equally, so no position-count update is needed.
    */
  def replaceHead(newState: GameState): GameSnapshot =
    history match
      case Nil            => this
      case (m, _) :: rest => copy(history = (m, newState) :: rest)

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
    * positionCounts map is derived by folding Zobrist.hash across the
    * initial state and every historical state.
    */
  def fromHistory(
      gameId: GameId,
      initialState: GameState,
      history: List[(Move, GameState)]
  ): GameSnapshot =
    val allStates = initialState :: history.map(_._2)
    val counts = allStates.foldLeft(Map.empty[Long, Int]) { (acc, s) =>
      val k = Zobrist.hash(s)
      acc.updatedWith(k)(_.map(_ + 1).orElse(Some(1)))
    }
    GameSnapshot(gameId, initialState, history, Nil, counts)

case class SessionState(
    game: GameSnapshot,
    error: Option[String] = None,
    output: Option[String] = None
):
  export game.{gameId, initialState, history, redoStack, state, moves}

package chess.model

import chess.model.board.{GameState, Move}
import chess.model.piece.Color
import chess.model.rules.Zobrist
import chess.notation.SanSerializer
import zio.{IO, ZIO}

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
  */
case class GameSnapshot(
    gameId: GameId,
    initialState: GameState,
    history: List[(Move, GameState)],
    redoStack: List[(Move, GameState)],
    positionCounts: Map[Long, Int],
    /** Pre-computed SAN log mirroring `history` in chronological order
      * (oldest first). Maintained incrementally by [[recordMove]] /
      * [[undoOnce]] / [[redoOnce]] so [[chess.gameservice.GrpcMappers.toStateReply]]
      * doesn't have to re-walk the entire history and re-run
      * [[chess.notation.SanSerializer.deriveMoveLog]] on every reply.
      *
      * The accompanying `redoMoveLog` parallels `redoStack`, also in
      * chronological order (the move that was most-recently undone is
      * at the head — the same shape as `redoStack`).
      */
    moveLog: List[(Color, String)] = Nil,
    redoMoveLog: List[(Color, String)] = Nil
):
  def state: GameState = history.headOption.map(_._2).getOrElse(initialState)
  def moves: List[Move] = history.reverse.map(_._1)

  /** Advance history by one move, updating positionCounts and clearing redo.
    * `san` is appended to [[moveLog]] paired with the *pre-move* active color
    * (the side whose turn it just was), matching the encoding
    * [[chess.notation.SanSerializer.deriveMoveLog]] produced.
    */
  def recordMove(move: Move, newState: GameState, san: String): GameSnapshot =
    val key       = Zobrist.hash(newState)
    val preColor  = state.activeColor
    copy(
      history = (move, newState) :: history,
      redoStack = Nil,
      positionCounts =
        positionCounts.updatedWith(key)(_.map(_ + 1).orElse(Some(1))),
      moveLog = moveLog :+ ((preColor, san)),
      redoMoveLog = Nil
    )

  /** Pop the top of history onto redoStack. `None` when there is nothing to
    * undo, so the caller decides how to surface that to the user.
    */
  def undoOnce: Option[GameSnapshot] = history match
    case Nil => None
    case (move, state) :: rest =>
      val key = Zobrist.hash(state)
      val (newMoveLog, redoEntry) = moveLog match
        case init :+ last => (init, last :: redoMoveLog)
        case Nil          => (Nil, redoMoveLog)
      Some(
        copy(
          history = rest,
          redoStack = (move, state) :: redoStack,
          positionCounts = positionCounts.updatedWith(key) {
            case Some(n) if n > 1 => Some(n - 1)
            case _                => None
          },
          moveLog = newMoveLog,
          redoMoveLog = redoEntry
        )
      )

  /** Push the top of redoStack back onto history. `None` when empty. */
  def redoOnce: Option[GameSnapshot] = redoStack match
    case Nil => None
    case (move, state) :: rest =>
      val key = Zobrist.hash(state)
      val (newMoveLog, newRedoLog) = redoMoveLog match
        case head :: tail => (moveLog :+ head, tail)
        case Nil          => (moveLog, Nil)
      Some(
        copy(
          history = (move, state) :: history,
          redoStack = rest,
          positionCounts =
            positionCounts.updatedWith(key)(_.map(_ + 1).orElse(Some(1))),
          moveLog = newMoveLog,
          redoMoveLog = newRedoLog
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
      case Nil            => this
      case (m, _) :: rest => copy(history = (m, newState) :: rest)

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
    * state and every historical state. The SAN move-log is derived in one
    * pass via [[chess.notation.SanSerializer.deriveMoveLog]] and cached on
    * the returned snapshot so subsequent state-replies don't pay the cost.
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
        GameSnapshot(
          gameId,
          initialState,
          history,
          Nil,
          counts,
          moveLog = log,
          redoMoveLog = Nil
        )
      }

case class SessionState(
    game: GameSnapshot,
    error: Option[String] = None,
    output: Option[String] = None
):
  export game.{gameId, initialState, history, redoStack, state, moves}

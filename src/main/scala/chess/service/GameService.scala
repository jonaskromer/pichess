package chess.service

import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.{GameState, Move}
import chess.repository.GameRepository
import zio.*

/** Business-logic layer for managing chess games.
  *
  * Responsibilities:
  *   - Issuing fresh game IDs and seeding the initial board
  *   - Loading games from serialized formats, auto-detecting the format
  *   - Applying moves (parsing + validation + persistence)
  *   - Reading and writing game state via the [[GameRepository]]
  *
  * Does NOT manage session state (undo/redo history, error messages,
  * flipped-board UI state) — that lives in [[chess.controller.GameController]]
  * atop a [[zio.stream.SubscriptionRef]] of [[chess.model.SessionState]].
  */
trait GameService:

  /** Start a new game from the standard initial position. Generates a
    * fresh UUID-based game ID and persists the initial state so subsequent
    * calls (makeMove, getState) can find it.
    */
  def newGame(): IO[GameError, GameEvent.GameStarted]

  /** Load a game from a serialized representation, auto-detecting the
    * format. Attempts parsing in this order:
    *   1. JSON (the GameState DTO emitted by [[chess.codec.JsonSerializer]])
    *   2. PGN (with optional `[FEN "…"]` header for a custom start)
    *   3. FEN (a single position, no move history)
    *
    * Returns the [[GameEvent.GameStarted]] with the initial state, along
    * with the replayed move history (empty for FEN/JSON). The resulting
    * game is persisted under a fresh ID; callers do not need to save it
    * separately.
    *
    * Fails with [[GameError.ParseError]] if none of the three formats
    * accept the input.
    */
  def loadGame(
      input: String
  ): IO[GameError, (GameEvent.GameStarted, List[(Move, GameState)])]

  /** Parse `rawInput` as a move against the game identified by `id`, apply
    * it, persist the new state, and return it alongside a
    * [[GameEvent.MoveMade]].
    *
    * `rawInput` is resolved through [[chess.notation.MoveParser]], which
    * accepts coordinate, castling, and SAN notations. The underlying state
    * transition is delegated to [[chess.model.rules.Game.applyMove]],
    * inheriting its error taxonomy.
    *
    * Fails with [[GameError.GameNotFound]] when `id` is unknown.
    */
  def makeMove(
      id: GameId,
      rawInput: String
  ): IO[GameError, (GameState, GameEvent.MoveMade)]

  /** Load the current persisted state for a game, or `None` if unknown. */
  def getState(id: GameId): IO[GameError, Option[GameState]]

  /** Persist `state` under `id`, overwriting any previous value. */
  def saveState(id: GameId, state: GameState): IO[GameError, Unit]

object GameService:
  def newGame(): ZIO[GameService, GameError, GameEvent.GameStarted] =
    ZIO.serviceWithZIO[GameService](_.newGame())

  def loadGame(
      input: String
  ): ZIO[
    GameService,
    GameError,
    (GameEvent.GameStarted, List[(Move, GameState)])
  ] =
    ZIO.serviceWithZIO[GameService](_.loadGame(input))

  def makeMove(
      id: GameId,
      rawInput: String
  ): ZIO[GameService, GameError, (GameState, GameEvent.MoveMade)] =
    ZIO.serviceWithZIO[GameService](_.makeMove(id, rawInput))

  def getState(id: GameId): ZIO[GameService, GameError, Option[GameState]] =
    ZIO.serviceWithZIO[GameService](_.getState(id))

  def saveState(
      id: GameId,
      state: GameState
  ): ZIO[GameService, GameError, Unit] =
    ZIO.serviceWithZIO[GameService](_.saveState(id, state))

  val layer: URLayer[GameRepository, GameService] = GameServiceLive.layer

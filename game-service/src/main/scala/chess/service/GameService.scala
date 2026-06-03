package chess.service

import chess.events.{GameDomainEvent, GameEventProducer}
import chess.persistence.{GameRepository, Mutation}
import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.{GameState, Move}
import zio.*

/** Convenience alias — the [[Mutation]] shape used throughout the
  * game-service: key is the [[GameId]], aggregate is [[GameState]],
  * events are [[GameDomainEvent]]s that flow to the Kafka topic.
  */
type GameMutation = Mutation[GameId, GameState, GameDomainEvent]

/** Business-logic layer for managing chess games.
  *
  * Responsibilities:
  *   - Issuing fresh game IDs and seeding the initial board
  *   - Loading games from serialized formats, auto-detecting the format
  *   - Applying moves (parsing + validation + building a pending mutation)
  *   - Reading and writing game state via the [[GameRepository]]
  *
  * Mutating operations like [[makeMove]] return a [[Mutation]] describing
  * the pending change rather than persisting eagerly. The caller (e.g.
  * [[chess.controller.GameController]]) can amend the Mutation with
  * cross-cutting state transitions it has unique visibility into — for
  * example, the per-session repetition history needed to detect a
  * fivefold-repetition auto-draw — and then calls [[commit]] exactly
  * once. [[Mutation.commit]] skips the save when the final state is
  * value-equal to the loaded pre-state, so amendments that turn out to
  * be no-ops don't generate write traffic.
  *
  * Does NOT manage session state (undo/redo history, error messages,
  * flipped-board UI state) — that lives in [[chess.controller.GameController]]
  * atop a [[zio.stream.SubscriptionRef]] of [[chess.model.SessionState]].
  */
trait GameService:

  /** Start a new game from the standard initial position. Generates a fresh
    * UUID-based game ID and persists the initial state so subsequent calls
    * (makeMove, getState) can find it.
    */
  def newGame(): IO[GameError, GameEvent.GameStarted]

  /** Load a game from a serialized representation, auto-detecting the format.
    * Attempts parsing in this order:
    *   1. JSON (the GameState DTO emitted by [[chess.codec.JsonSerializer]]) 2.
    *      PGN (with optional `[FEN "…"]` header for a custom start) 3. FEN (a
    *      single position, no move history)
    *
    * Returns the [[GameEvent.GameStarted]] with the initial state, along with
    * the replayed move history (empty for FEN/JSON). The resulting game is
    * persisted under a fresh ID; callers do not need to save it separately.
    *
    * Fails with [[GameError.ParseError]] if none of the three formats accept
    * the input.
    */
  def loadGame(
      input: String
  ): IO[GameError, (GameEvent.GameStarted, List[(Move, GameState)])]

  /** Parse `rawInput` as a move against the game identified by `id` and
    * apply it. Returns the in-memory [[GameEvent.MoveMade]] (carrying the
    * parsed `Move` and SAN string the session needs) plus a pending
    * [[Mutation]] describing the persistence + publish work. The caller
    * may amend the Mutation (e.g. attach a fivefold-draw amendment) and
    * MUST eventually pass it to [[commit]] — without that, no state is
    * persisted and no event is published.
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
  ): IO[GameError, (GameEvent.MoveMade, GameMutation)]

  /** Load the current persisted state for a game, or `None` if unknown. */
  def getState(id: GameId): IO[GameError, Option[GameState]]

  /** Persist `state` under `id`, overwriting any previous value. Direct
    * escape hatch for operations that don't fit the mutation flow
    * (e.g. undo / redo / claim-draw / forfeit, where the state is
    * derived from the session, not loaded from the store).
    */
  def saveState(id: GameId, state: GameState): IO[GameError, Unit]

  /** Persist the final state and publish the accumulated events from
    * `mutation`. The save is skipped if `mutation.changed` is false
    * (final state value-equal to the loaded pre-state).
    */
  def commit(mutation: GameMutation): IO[GameError, Unit]

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
  ): ZIO[GameService, GameError, (GameEvent.MoveMade, GameMutation)] =
    ZIO.serviceWithZIO[GameService](_.makeMove(id, rawInput))

  def getState(id: GameId): ZIO[GameService, GameError, Option[GameState]] =
    ZIO.serviceWithZIO[GameService](_.getState(id))

  def saveState(
      id: GameId,
      state: GameState
  ): ZIO[GameService, GameError, Unit] =
    ZIO.serviceWithZIO[GameService](_.saveState(id, state))

  def commit(
      mutation: GameMutation
  ): ZIO[GameService, GameError, Unit] =
    ZIO.serviceWithZIO[GameService](_.commit(mutation))

  val layer: URLayer[GameRepository & GameEventProducer, GameService] =
    GameServiceLive.layer

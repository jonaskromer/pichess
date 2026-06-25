package chess.service

import java.util.concurrent.TimeUnit

import zio.*

import chess.codec.{FenParserRegex, FenSerializer, JsonParser, PgnParser}
import chess.events.{GameDomainEvent, GameEventProducer}
import chess.model.board.{GameState, Move}
import chess.model.rules.Game
import chess.model.{GameError, GameEvent, GameId}
import chess.notation.{MoveParser, SanSerializer}
import chess.persistence.{GameRepository, Mutation}

final class GameServiceLive(
    store: GameRepository,
    producer: GameEventProducer
) extends GameService:

  private val now: UIO[Long] = Clock.currentTime(TimeUnit.MILLISECONDS)

  def newGame(): IO[GameError, GameEvent.GameStarted] =
    for
      id <- Random.nextUUID.map(_.toString)
      state = GameState.initial
      _ <- store.save(id, state)
      ts <- now
      _ <- producer.publish(
        GameDomainEvent
          .GameStarted(id, FenSerializer.serialize(state), ts)
      )
    yield GameEvent.GameStarted(id, state)

  def loadGame(
      input: String
  ): IO[GameError, (GameEvent.GameStarted, List[(Move, GameState)])] =
    val tryJson = JsonParser
      .parse(input)
      .map(state => (state, List.empty[(Move, GameState)]))

    val tryFen = FenParserRegex
      .parse(input)
      .map(state => (state, List.empty[(Move, GameState)]))

    val tryPgn = PgnParser
      .parse(input)
      .map(pgn => (pgn.initialState, pgn.history))

    val parsed = tryJson.orElse(tryPgn).orElse(tryFen)

    parsed.flatMap { case (initialState, history) =>
      val currentState = history.lastOption.map(_._2).getOrElse(initialState)
      for
        id <- Random.nextUUID.map(_.toString)
        _ <- store.save(id, currentState)
        ts <- now
        _ <- producer.publish(
          GameDomainEvent.GameLoaded(
            gameId = id,
            resultingFen = FenSerializer.serialize(currentState),
            initialFen = FenSerializer.serialize(initialState),
            historyMoves = history.size,
            occurredAt = ts
          )
        )
      yield (GameEvent.GameStarted(id, initialState), history)
    }

  // Computes the next state + the MoveMade events (both flavours) but
  // does NOT persist. The persistence + Kafka publish happens later in
  // `commit(mutation)`, possibly after the caller has amended the
  // mutation with cross-cutting state transitions (e.g. a
  // fivefold-repetition draw that only the controller can see). The
  // single-commit shape is what lets us collapse what used to be two
  // sequential repository writes per move into one.
  def makeMove(
      id: GameId,
      rawInput: String
  ): IO[GameError, (GameEvent.MoveMade, GameMutation)] =
    for
      stateOpt <- store.load(id)
      state <- ZIO
        .fromOption(stateOpt)
        .orElseFail(GameError.GameNotFound(id))
      move <- MoveParser.parse(rawInput, state)
      newState <- Game.applyMove(state, move)
      // SAN derivation needs the pre-move state; if it fails for any reason
      // (shouldn't, given Game.applyMove just succeeded) we fall back to the
      // coordinate string so the event always has a non-empty `san` field.
      // Evaluate the fallback eagerly into a val so scoverage tracks it
      // as a regular statement (the by-name argument form leaves an
      // unevaluated lambda that the happy path can't reach).
      coordStr = coordOf(move)
      san <- SanSerializer.toSan(move, state).orElseSucceed(coordStr)
      ts <- now
      domainEvent = GameDomainEvent.MoveMade(
        gameId = id,
        resultingFen = FenSerializer.serialize(newState),
        moveCoord = coordStr,
        san = san,
        occurredAt = ts
      )
      gameplayEvent: GameEvent.MoveMade = GameEvent.MoveMade(
        id,
        move,
        newState,
        san
      )
    yield (gameplayEvent, Mutation.from(id, state, newState, domainEvent))

  def getState(id: GameId): IO[GameError, Option[GameState]] =
    store.load(id)

  def saveState(id: GameId, state: GameState): IO[GameError, Unit] =
    store.save(id, state)

  def commit(mutation: GameMutation): IO[GameError, Unit] =
    Mutation.commit[Any, GameError, GameId, GameState, GameDomainEvent](
      mutation,
      save = (id, s) => store.save(id, s),
      publish = ev => producer.publish(ev)
    )

  private def coordOf(move: Move): String =
    s"${move.from.col}${move.from.row}-${move.to.col}${move.to.row}"

object GameServiceLive:
  val layer: URLayer[GameRepository & GameEventProducer, GameService] =
    ZLayer.fromFunction(GameServiceLive(_, _))

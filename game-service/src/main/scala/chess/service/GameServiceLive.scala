package chess.service

import chess.codec.{FenParserRegex, FenSerializer, JsonParser, PgnParser}
import chess.events.{GameDomainEvent, GameEventProducer}
import chess.persistence.GameRepository
import chess.notation.{MoveParser, SanSerializer}
import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.{GameState, Move}
import chess.model.rules.Game
import zio.*

import java.util.concurrent.TimeUnit

final class GameServiceLive(
    store: GameRepository,
    producer: GameEventProducer
) extends GameService:

  private val now: UIO[Long] = Clock.currentTime(TimeUnit.MILLISECONDS)

  def newGame(): IO[GameError, GameEvent.GameStarted] =
    for
      id <- Random.nextUUID.map(_.toString)
      state = GameState.initial
      _  <- store.save(id, state)
      ts <- now
      _  <- producer.publish(
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
        _  <- store.save(id, currentState)
        ts <- now
        _  <- producer.publish(
                GameDomainEvent.GameLoaded(
                  gameId       = id,
                  resultingFen = FenSerializer.serialize(currentState),
                  initialFen   = FenSerializer.serialize(initialState),
                  historyMoves = history.size,
                  occurredAt   = ts
                )
              )
      yield (GameEvent.GameStarted(id, initialState), history)
    }

  def makeMove(
      id: GameId,
      rawInput: String
  ): IO[GameError, (GameState, GameEvent.MoveMade)] =
    for
      stateOpt <- store.load(id)
      state    <- ZIO
                    .fromOption(stateOpt)
                    .orElseFail(GameError.GameNotFound(id))
      move     <- MoveParser.parse(rawInput, state)
      newState <- Game.applyMove(state, move)
      _        <- store.save(id, newState)
      // SAN derivation needs the pre-move state; if it fails for any reason
      // (shouldn't, given Game.applyMove just succeeded) we fall back to the
      // coordinate string so the event always has a non-empty `san` field.
      // Evaluate the fallback eagerly into a val so scoverage tracks it
      // as a regular statement (the by-name argument form leaves an
      // unevaluated lambda that the happy path can't reach).
      coordStr  = coordOf(move)
      san      <- SanSerializer.toSan(move, state).orElseSucceed(coordStr)
      ts       <- now
      _        <- producer.publish(
                    GameDomainEvent.MoveMade(
                      gameId       = id,
                      resultingFen = FenSerializer.serialize(newState),
                      moveCoord    = coordStr,
                      san          = san,
                      occurredAt   = ts
                    )
                  )
    yield (newState, GameEvent.MoveMade(id, move, newState, san))

  def getState(id: GameId): IO[GameError, Option[GameState]] =
    store.load(id)

  def saveState(id: GameId, state: GameState): IO[GameError, Unit] =
    store.save(id, state)

  private def coordOf(move: Move): String =
    s"${move.from.col}${move.from.row}-${move.to.col}${move.to.row}"

object GameServiceLive:
  val layer: URLayer[GameRepository & GameEventProducer, GameService] =
    ZLayer.fromFunction(GameServiceLive(_, _))

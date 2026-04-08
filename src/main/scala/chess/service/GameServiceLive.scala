package chess.service

import chess.codec.{FenParserRegex, JsonParser, PgnParser}
import chess.controller.MoveParser
import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.{GameState, Move}
import chess.model.rules.Game
import chess.repository.GameRepository
import zio.*

final class GameServiceLive(repo: GameRepository) extends GameService:

  def newGame(): IO[GameError, GameEvent.GameStarted] =
    for
      id <- Random.nextUUID.map(_.toString)
      state = GameState.initial
      _ <- repo.save(id, state)
    yield GameEvent.GameStarted(id, state)

  def loadGame(
      input: String
  ): IO[GameError, (GameEvent.GameStarted, List[Move], GameState)] =
    val tryJson = ZIO
      .fromEither(JsonParser.parse(input))
      .mapError(GameError.ParseError(_))
      .map(state => (state, List.empty[Move], state))

    val tryFen = ZIO
      .fromEither(FenParserRegex.parse(input))
      .mapError(GameError.ParseError(_))
      .map(state => (state, List.empty[Move], state))

    val tryPgn = PgnParser
      .parse(input)
      .map(pgn => (pgn.initialState, pgn.moves, pgn.state))

    val parsed = tryJson.orElse(tryPgn).orElse(tryFen)

    parsed.flatMap { case (initialState, moves, currentState) =>
      for
        id <- Random.nextUUID.map(_.toString)
        _ <- repo.save(id, currentState)
      yield (GameEvent.GameStarted(id, initialState), moves, currentState)
    }

  def makeMove(
      id: GameId,
      rawInput: String
  ): IO[GameError, (GameState, GameEvent.MoveMade)] =
    for
      stateOpt <- repo.load(id)
      state <- ZIO
        .fromOption(stateOpt)
        .orElseFail(GameError.GameNotFound(id))
      move <- MoveParser.parse(rawInput, state)
      newState <- Game.applyMove(state, move)
      _ <- repo.save(id, newState)
    yield (newState, GameEvent.MoveMade(id, move, newState))

  def getState(id: GameId): IO[GameError, Option[GameState]] =
    repo.load(id)

  def saveState(id: GameId, state: GameState): IO[GameError, Unit] =
    repo.save(id, state)

object GameServiceLive:
  val layer: URLayer[GameRepository, GameService] =
    ZLayer.fromFunction(GameServiceLive(_))

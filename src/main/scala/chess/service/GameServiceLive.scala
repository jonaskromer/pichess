package chess.service

import chess.codec.{FenParserRegex, JsonParser, PgnParser}
import chess.controller.MoveParser
import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.GameState
import chess.model.piece.Color
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
  ): IO[GameError, (GameEvent.GameStarted, List[(Color, String)])] =
    val tryJson = ZIO
      .fromEither(JsonParser.parse(input))
      .mapError(GameError.ParseError(_))
      .map(state => (state, List.empty[(Color, String)]))

    val tryFen = ZIO
      .fromEither(FenParserRegex.parse(input))
      .mapError(GameError.ParseError(_))
      .map(state => (state, List.empty[(Color, String)]))

    val tryPgn = PgnParser
      .parse(input)
      .map(pgn => (pgn.state, pgn.moveLog))

    val parsed = tryJson.orElse(tryPgn).orElse(tryFen)

    parsed.flatMap { case (state, moveLog) =>
      for
        id <- Random.nextUUID.map(_.toString)
        _ <- repo.save(id, state)
      yield (GameEvent.GameStarted(id, state), moveLog)
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

object GameServiceLive:
  val layer: URLayer[GameRepository, GameService] =
    ZLayer.fromFunction(GameServiceLive(_))

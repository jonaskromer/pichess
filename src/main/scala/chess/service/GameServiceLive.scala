package chess.service

import chess.controller.{MoveParser, SanResolver}
import chess.model.{GameEvent, GameId}
import chess.model.board.GameState
import chess.model.rules.Game
import chess.repository.GameRepository
import zio.*

final class GameServiceLive(repo: GameRepository) extends GameService:

  def newGame(): Task[GameEvent.GameStarted] =
    for
      id <- ZIO.attempt(java.util.UUID.randomUUID().toString)
      state = GameState.initial
      _ <- repo.save(id, state)
    yield GameEvent.GameStarted(id, state)

  def makeMove(
      id: GameId,
      rawInput: String
  ): Task[(GameState, GameEvent.MoveMade)] =
    for
      stateOpt <- repo.load(id)
      state <- ZIO
        .fromOption(stateOpt)
        .orElseFail(chess.model.GameError.GameNotFound(id))
      move <- ZIO.fromEither(
        MoveParser.parse(rawInput).flatMap(SanResolver.resolve(_, state))
      )
      newState <- ZIO.fromEither(Game.applyMove(state, move))
      _ <- repo.save(id, newState)
    yield (newState, GameEvent.MoveMade(id, move, newState))

  def getState(id: GameId): Task[Option[GameState]] =
    repo.load(id)

object GameServiceLive:
  val layer: URLayer[GameRepository, GameService] =
    ZLayer.fromFunction(GameServiceLive(_))

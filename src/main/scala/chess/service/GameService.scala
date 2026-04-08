package chess.service

import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.GameState
import chess.repository.GameRepository
import zio.*

trait GameService:
  def newGame(): IO[GameError, GameEvent.GameStarted]
  def newGameFromFen(fen: String): IO[GameError, GameEvent.GameStarted]
  def makeMove(
      id: GameId,
      rawInput: String
  ): IO[GameError, (GameState, GameEvent.MoveMade)]
  def getState(id: GameId): IO[GameError, Option[GameState]]

object GameService:
  def newGame(): ZIO[GameService, GameError, GameEvent.GameStarted] =
    ZIO.serviceWithZIO[GameService](_.newGame())

  def newGameFromFen(
      fen: String
  ): ZIO[GameService, GameError, GameEvent.GameStarted] =
    ZIO.serviceWithZIO[GameService](_.newGameFromFen(fen))

  def makeMove(
      id: GameId,
      rawInput: String
  ): ZIO[GameService, GameError, (GameState, GameEvent.MoveMade)] =
    ZIO.serviceWithZIO[GameService](_.makeMove(id, rawInput))

  def getState(id: GameId): ZIO[GameService, GameError, Option[GameState]] =
    ZIO.serviceWithZIO[GameService](_.getState(id))

  val layer: URLayer[GameRepository, GameService] = GameServiceLive.layer

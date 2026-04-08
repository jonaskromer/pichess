package chess.service

import chess.model.{GameError, GameEvent, GameId}
import chess.model.board.{GameState, Move}
import chess.repository.GameRepository
import zio.*

trait GameService:
  def newGame(): IO[GameError, GameEvent.GameStarted]
  def loadGame(
      input: String
  ): IO[GameError, (GameEvent.GameStarted, List[Move], GameState)]
  def makeMove(
      id: GameId,
      rawInput: String
  ): IO[GameError, (GameState, GameEvent.MoveMade)]
  def getState(id: GameId): IO[GameError, Option[GameState]]
  def saveState(id: GameId, state: GameState): IO[GameError, Unit]

object GameService:
  def newGame(): ZIO[GameService, GameError, GameEvent.GameStarted] =
    ZIO.serviceWithZIO[GameService](_.newGame())

  def loadGame(
      input: String
  ): ZIO[GameService, GameError, (GameEvent.GameStarted, List[Move], GameState)] =
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

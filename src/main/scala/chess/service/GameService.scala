package chess.service

import chess.model.{GameEvent, GameId}
import chess.model.board.GameState
import chess.repository.GameRepository
import zio.*

trait GameService:
  def newGame(): Task[GameEvent.GameStarted]
  def makeMove(id: GameId, rawInput: String): Task[(GameState, GameEvent.MoveMade)]
  def getState(id: GameId): Task[Option[GameState]]

object GameService:
  def newGame(): ZIO[GameService, Throwable, GameEvent.GameStarted] =
    ZIO.serviceWithZIO[GameService](_.newGame())

  def makeMove(
      id: GameId,
      rawInput: String
  ): ZIO[GameService, Throwable, (GameState, GameEvent.MoveMade)] =
    ZIO.serviceWithZIO[GameService](_.makeMove(id, rawInput))

  def getState(id: GameId): ZIO[GameService, Throwable, Option[GameState]] =
    ZIO.serviceWithZIO[GameService](_.getState(id))

  val layer: URLayer[GameRepository, GameService] = GameServiceLive.layer

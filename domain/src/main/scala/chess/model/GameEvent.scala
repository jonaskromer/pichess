package chess.model

import chess.model.board.{GameState, Move}

enum GameEvent:
  case GameStarted(gameId: GameId, initialState: GameState)
  case MoveMade(
      gameId: GameId,
      move: Move,
      resultingState: GameState,
      san: String
  )
  case InvalidMoveAttempted(gameId: GameId, reason: String)

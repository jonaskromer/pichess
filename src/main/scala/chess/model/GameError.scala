package chess.model

enum GameError(val message: String) extends Exception(message):
  case ParseError(msg: String) extends GameError(msg)
  case InvalidMove(msg: String) extends GameError(msg)
  case GameNotFound(id: GameId) extends GameError(s"Game not found: $id")

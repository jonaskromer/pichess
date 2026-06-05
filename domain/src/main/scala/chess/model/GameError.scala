package chess.model

enum GameError(val message: String) extends Exception(message):
  case ParseError(msg: String) extends GameError(msg)
  case InvalidMove(msg: String) extends GameError(msg)
  case GameNotFound(id: GameId) extends GameError(s"Game not found: $id")

  /** Transport / infrastructure failure talking to a downstream service — e.g.
    * the repository microservice is unreachable or returned 5xx. Kept distinct
    * from [[ParseError]] so retry policies can target it.
    */
  case InfrastructureError(msg: String) extends GameError(msg)

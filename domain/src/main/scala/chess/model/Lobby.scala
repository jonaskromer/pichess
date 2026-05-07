package chess.model

enum LobbyStatus:
  case Waiting
  case Full
  case Started
  case Closed

  def isTerminal: Boolean = this match
    case Closed => true
    case _      => false

case class Lobby(
    id: LobbyId,
    inviteCode: InviteCode,
    hostNickname: String,
    guestNickname: Option[String],
    status: LobbyStatus,
    createdAt: Long,
    gameId: Option[GameId]
):
  def join(nickname: String): Either[LobbyError, Lobby] = status match
    case LobbyStatus.Waiting =>
      Right(copy(guestNickname = Some(nickname), status = LobbyStatus.Full))
    case _ => Left(LobbyError.LobbyNotJoinable(id, status))

  def start(gameId: GameId): Either[LobbyError, Lobby] = status match
    case LobbyStatus.Full =>
      Right(copy(status = LobbyStatus.Started, gameId = Some(gameId)))
    case _ => Left(LobbyError.LobbyNotStartable(id, status))

  def close: Lobby = copy(status = LobbyStatus.Closed)

package chess.model

enum LobbyStatus:
  case Waiting
  case Full
  case Started
  case Closed

  def isTerminal: Boolean = this match
    case Closed => true
    case _      => false

/** Whether a lobby shows up in the public lobby browser. Public lobbies
  * appear in `GET /lobbies/public`; private lobbies are reachable only by
  * invite code.
  */
enum LobbyVisibility:
  case Public
  case Private

case class Lobby(
    id: LobbyId,
    inviteCode: InviteCode,
    hostNickname: String,
    hostSessionId: String,
    guestNickname: Option[String],
    guestSessionId: Option[String],
    visibility: LobbyVisibility,
    allowUndo: Boolean,
    allowSpectate: Boolean,
    spectatorLimit: Int,
    status: LobbyStatus,
    createdAt: Long,
    gameId: Option[GameId]
):
  /** A guest joins by supplying their nickname AND their session id; the
    * gateway's role registry needs both names tied to specific sessions
    * once the game starts. Joining a Public lobby is allowed; Private
    * just means it's not in the browser — the join endpoint itself
    * doesn't gate on visibility.
    */
  def join(nickname: String, sessionId: String): Either[LobbyError, Lobby] =
    status match
      case LobbyStatus.Waiting =>
        Right(
          copy(
            guestNickname = Some(nickname),
            guestSessionId = Some(sessionId),
            status = LobbyStatus.Full
          )
        )
      case _ => Left(LobbyError.LobbyNotJoinable(id, status))

  def start(gameId: GameId): Either[LobbyError, Lobby] = status match
    case LobbyStatus.Full =>
      Right(copy(status = LobbyStatus.Started, gameId = Some(gameId)))
    case _ => Left(LobbyError.LobbyNotStartable(id, status))

  def close: Lobby = copy(status = LobbyStatus.Closed)

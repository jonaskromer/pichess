package chess.model

enum LobbyError(val message: String) extends Exception(message):
  case LobbyNotFound(id: LobbyId)
      extends LobbyError(s"Lobby not found: $id")
  case InviteCodeNotFound(code: String)
      extends LobbyError(s"No lobby found for invite code: $code")
  case InvalidInviteCode(raw: String)
      extends LobbyError(s"Malformed invite code: $raw")
  case NicknameInvalid(reason: String) extends LobbyError(reason)
  case LobbyNotJoinable(id: LobbyId, status: LobbyStatus)
      extends LobbyError(s"Lobby $id cannot be joined in state $status")
  case LobbyNotStartable(id: LobbyId, status: LobbyStatus)
      extends LobbyError(s"Lobby $id cannot be started in state $status")
  case InfrastructureError(msg: String) extends LobbyError(msg)

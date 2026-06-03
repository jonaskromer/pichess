package chess.lobby

import java.util.concurrent.TimeUnit

import zio.*

import chess.model.{
  GameId,
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.LobbyRepository

/** Business logic for the lobby microservice. Talks to a swappable
  * [[LobbyRepository]] for storage; nothing Postgres / Mongo / etc.-specific
  * leaks into this trait.
  *
  * Settings carried from `createLobby` (visibility, allowUndo, allowSpectate,
  * spectatorLimit) live on the resulting [[Lobby]] and are read by callers
  * downstream — game-mode rules at the gateway, the public-lobby browser,
  * etc. The service itself doesn't gate on them.
  */
trait LobbyService:
  def createLobby(input: NewLobbyInput): IO[LobbyError, Lobby]
  def joinLobby(
      code: InviteCode,
      guestNickname: String,
      guestSessionId: String
  ): IO[LobbyError, Lobby]
  def getLobby(id: LobbyId): IO[LobbyError, Option[Lobby]]
  def findByCode(code: InviteCode): IO[LobbyError, Option[Lobby]]
  def listPublic(): IO[LobbyError, List[Lobby]]
  def startGame(id: LobbyId, gameId: GameId): IO[LobbyError, Lobby]
  def closeLobby(id: LobbyId): IO[LobbyError, Unit]

/** Inputs that flow from the create endpoint into [[LobbyService]]. Bundled
  * into a case class so the call site stays readable as the field count
  * grows.
  */
final case class NewLobbyInput(
    hostNickname: String,
    hostSessionId: String,
    visibility: LobbyVisibility,
    allowUndo: Boolean,
    allowSpectate: Boolean,
    spectatorLimit: Int
)

object LobbyService:
  def createLobby(input: NewLobbyInput): ZIO[LobbyService, LobbyError, Lobby] =
    ZIO.serviceWithZIO[LobbyService](_.createLobby(input))

  def joinLobby(
      code: InviteCode,
      guestNickname: String,
      guestSessionId: String
  ): ZIO[LobbyService, LobbyError, Lobby] =
    ZIO.serviceWithZIO[LobbyService](
      _.joinLobby(code, guestNickname, guestSessionId)
    )

  def getLobby(id: LobbyId): ZIO[LobbyService, LobbyError, Option[Lobby]] =
    ZIO.serviceWithZIO[LobbyService](_.getLobby(id))

  def findByCode(
      code: InviteCode
  ): ZIO[LobbyService, LobbyError, Option[Lobby]] =
    ZIO.serviceWithZIO[LobbyService](_.findByCode(code))

  def listPublic(): ZIO[LobbyService, LobbyError, List[Lobby]] =
    ZIO.serviceWithZIO[LobbyService](_.listPublic())

  def startGame(
      id: LobbyId,
      gameId: GameId
  ): ZIO[LobbyService, LobbyError, Lobby] =
    ZIO.serviceWithZIO[LobbyService](_.startGame(id, gameId))

  def closeLobby(id: LobbyId): ZIO[LobbyService, LobbyError, Unit] =
    ZIO.serviceWithZIO[LobbyService](_.closeLobby(id))

  val layer: URLayer[LobbyRepository & GatewayCoordinator, LobbyService] =
    ZLayer.fromFunction(LobbyServiceLive(_, _))

final class LobbyServiceLive(
    repo: LobbyRepository,
    gateway: GatewayCoordinator
) extends LobbyService:

  /** Generate codes through a unique-by-construction loop. Cardinality of the
    * code space (~32^6 ≈ 1B) makes a single retry overwhelmingly likely to
    * succeed, but we still cap attempts to fail fast on a saturated DB
    * rather than spin forever.
    */
  private val MaxCodeAttempts = 8

  private def reserveUniqueCode(
      attempt: Int = 0
  ): IO[LobbyError, InviteCode] =
    if attempt >= MaxCodeAttempts then
      ZIO.fail(
        LobbyError.InfrastructureError(
          s"Could not reserve a unique invite code after $MaxCodeAttempts attempts"
        )
      )
    else
      for
        candidate <- InviteCode.random
        existing  <- repo.findByInviteCode(candidate)
        code      <- existing match
                       case None    => ZIO.succeed(candidate)
                       case Some(_) => reserveUniqueCode(attempt + 1)
      yield code

  def createLobby(input: NewLobbyInput): IO[LobbyError, Lobby] =
    val nick = input.hostNickname.trim
    val session = input.hostSessionId.trim
    if nick.isEmpty then
      ZIO.fail(LobbyError.NicknameInvalid("Host nickname must not be empty"))
    else if session.isEmpty then
      ZIO.fail(
        LobbyError.NicknameInvalid("Host sessionId must not be empty")
      )
    else
      for
        id    <- Random.nextUUID.map(_.toString)
        code  <- reserveUniqueCode()
        now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        lobby = Lobby(
                  id = id,
                  inviteCode = code,
                  hostNickname = nick,
                  hostSessionId = session,
                  guestNickname = None,
                  guestSessionId = None,
                  visibility = input.visibility,
                  allowUndo = input.allowUndo,
                  allowSpectate = input.allowSpectate,
                  spectatorLimit = input.spectatorLimit,
                  status = LobbyStatus.Waiting,
                  createdAt = now,
                  gameId = None
                )
        _     <- repo.create(lobby)
      yield lobby

  def joinLobby(
      code: InviteCode,
      guestNickname: String,
      guestSessionId: String
  ): IO[LobbyError, Lobby] =
    val nick = guestNickname.trim
    val session = guestSessionId.trim
    if nick.isEmpty then
      ZIO.fail(LobbyError.NicknameInvalid("Guest nickname must not be empty"))
    else if session.isEmpty then
      ZIO.fail(
        LobbyError.NicknameInvalid("Guest sessionId must not be empty")
      )
    else
      for
        existing <- repo
                      .findByInviteCode(code)
                      .someOrFail(LobbyError.InviteCodeNotFound(code.value))
        joined   <- ZIO.fromEither(existing.join(nick, session))
        _        <- repo.update(joined)
      yield joined

  def getLobby(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    repo.findById(id)

  def findByCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    repo.findByInviteCode(code)

  def listPublic(): IO[LobbyError, List[Lobby]] =
    repo.listPublicWaiting()

  def startGame(id: LobbyId, gameId: GameId): IO[LobbyError, Lobby] =
    for
      existing <- repo
                    .findById(id)
                    .someOrFail(LobbyError.LobbyNotFound(id))
      started  <- ZIO.fromEither(existing.start(gameId))
      _        <- repo.update(started)
      // Hand off the lobby's player sessions to the gateway so its
      // SessionRegistry switches the game from local-only to host+guest.
      // Coordinator failures are surfaced as InfrastructureError so the
      // host sees something actionable; the lobby is still updated either
      // way (it's the source of truth).
      _        <- gateway
                    .registerPlayers(
                      gameId,
                      started.hostSessionId,
                      started.guestSessionId
                    )
                    .mapError(t =>
                      LobbyError.InfrastructureError(
                        s"Gateway hand-off failed: ${t.getMessage}"
                      )
                    )
    yield started

  def closeLobby(id: LobbyId): IO[LobbyError, Unit] =
    for
      existing <- repo
                    .findById(id)
                    .someOrFail(LobbyError.LobbyNotFound(id))
      done     <- repo.update(existing.close)
    yield done

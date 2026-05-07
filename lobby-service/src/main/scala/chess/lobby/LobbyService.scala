package chess.lobby

import chess.model.{
  GameId,
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus
}
import chess.persistence.LobbyRepository
import zio.*

import java.util.concurrent.TimeUnit

/** Business logic for the lobby microservice. Talks to a swappable
  * [[LobbyRepository]] for storage; nothing Postgres / Mongo / etc.-specific
  * leaks into this trait.
  */
trait LobbyService:
  def createLobby(hostNickname: String): IO[LobbyError, Lobby]
  def joinLobby(
      code: InviteCode,
      guestNickname: String
  ): IO[LobbyError, Lobby]
  def getLobby(id: LobbyId): IO[LobbyError, Option[Lobby]]
  def startGame(id: LobbyId, gameId: GameId): IO[LobbyError, Lobby]
  def closeLobby(id: LobbyId): IO[LobbyError, Unit]

object LobbyService:
  def createLobby(
      hostNickname: String
  ): ZIO[LobbyService, LobbyError, Lobby] =
    ZIO.serviceWithZIO[LobbyService](_.createLobby(hostNickname))

  def joinLobby(
      code: InviteCode,
      guestNickname: String
  ): ZIO[LobbyService, LobbyError, Lobby] =
    ZIO.serviceWithZIO[LobbyService](_.joinLobby(code, guestNickname))

  def getLobby(id: LobbyId): ZIO[LobbyService, LobbyError, Option[Lobby]] =
    ZIO.serviceWithZIO[LobbyService](_.getLobby(id))

  def startGame(
      id: LobbyId,
      gameId: GameId
  ): ZIO[LobbyService, LobbyError, Lobby] =
    ZIO.serviceWithZIO[LobbyService](_.startGame(id, gameId))

  def closeLobby(id: LobbyId): ZIO[LobbyService, LobbyError, Unit] =
    ZIO.serviceWithZIO[LobbyService](_.closeLobby(id))

  val layer: URLayer[LobbyRepository, LobbyService] =
    ZLayer.fromFunction(LobbyServiceLive(_))

final class LobbyServiceLive(repo: LobbyRepository) extends LobbyService:

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

  def createLobby(hostNickname: String): IO[LobbyError, Lobby] =
    val nick = hostNickname.trim
    if nick.isEmpty then
      ZIO.fail(LobbyError.NicknameInvalid("Host nickname must not be empty"))
    else
      for
        id    <- Random.nextUUID.map(_.toString)
        code  <- reserveUniqueCode()
        now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        lobby = Lobby(
                  id = id,
                  inviteCode = code,
                  hostNickname = nick,
                  guestNickname = None,
                  status = LobbyStatus.Waiting,
                  createdAt = now,
                  gameId = None
                )
        _     <- repo.create(lobby)
      yield lobby

  def joinLobby(
      code: InviteCode,
      guestNickname: String
  ): IO[LobbyError, Lobby] =
    val nick = guestNickname.trim
    if nick.isEmpty then
      ZIO.fail(LobbyError.NicknameInvalid("Guest nickname must not be empty"))
    else
      for
        existing <- repo
                      .findByInviteCode(code)
                      .someOrFail(LobbyError.InviteCodeNotFound(code.value))
        joined   <- ZIO.fromEither(existing.join(nick))
        _        <- repo.update(joined)
      yield joined

  def getLobby(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    repo.findById(id)

  def startGame(id: LobbyId, gameId: GameId): IO[LobbyError, Lobby] =
    for
      existing <- repo
                    .findById(id)
                    .someOrFail(LobbyError.LobbyNotFound(id))
      started  <- ZIO.fromEither(existing.start(gameId))
      _        <- repo.update(started)
    yield started

  def closeLobby(id: LobbyId): IO[LobbyError, Unit] =
    for
      existing <- repo
                    .findById(id)
                    .someOrFail(LobbyError.LobbyNotFound(id))
      done     <- repo.update(existing.close)
    yield done

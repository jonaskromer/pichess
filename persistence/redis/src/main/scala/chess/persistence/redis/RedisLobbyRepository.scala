package chess.persistence.redis

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId, LobbyStatus}
import chess.persistence.LobbyRepository
import zio.*
import zio.json.*
import zio.redis.Redis

/** Redis-backed `LobbyRepository`. Lobby state is JSON-encoded under
  * `lobby:{id}`; a secondary `lobby:invite:{code}` key maps invite codes to
  * lobby ids for the join lookup.
  *
  * Two-key writes here aren't atomic across the two SETs, but since invite
  * codes are reserved unique up-front and lobbies aren't deleted-then-
  * re-created with the same code, the small inconsistency window is benign.
  * If we needed strict atomicity we'd MULTI/EXEC the pair.
  */
final class RedisLobbyRepository(redis: Redis) extends LobbyRepository:

  import RedisLobbyRepository.given

  private def lobbyKey(id: LobbyId): String = s"lobby:$id"
  private def inviteKey(code: InviteCode): String = s"lobby:invite:${code.value}"

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    val payload = lobby.toJson
    (redis.set(lobbyKey(lobby.id), payload) *>
      redis.set(inviteKey(lobby.inviteCode), lobby.id))
      .unit
      .mapError(toInfraError)

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    redis
      .get(lobbyKey(id))
      .returning[String]
      .mapError(toInfraError)
      .flatMap(decode)

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    redis
      .get(inviteKey(code))
      .returning[String]
      .mapError(toInfraError)
      .flatMap {
        case Some(id) => findById(id)
        case None     => ZIO.succeed(None)
      }

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    // Invite code is immutable across a lobby's lifecycle, so updating is
    // just a single SET on the lobby payload.
    redis.set(lobbyKey(lobby.id), lobby.toJson).unit.mapError(toInfraError)

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    for
      existing <- findById(id)
      _        <- redis.del(lobbyKey(id)).mapError(toInfraError)
      _        <- existing match
                    case Some(l) =>
                      redis.del(inviteKey(l.inviteCode)).mapError(toInfraError)
                    case None => ZIO.unit
    yield ()

  private def decode(raw: Option[String]): IO[LobbyError, Option[Lobby]] =
    raw match
      case None => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[Lobby])
          .mapBoth(
            err => LobbyError.InfrastructureError(s"Invalid lobby JSON: $err"),
            Some(_)
          )

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Redis error: ${t.getMessage}")

object RedisLobbyRepository:

  // Internal JSON shape for Lobby persistence in Redis. Kept separate from
  // any HTTP wire codec so a future API field rename doesn't migrate stored
  // data unintentionally.
  private given JsonCodec[InviteCode] = JsonCodec[String].transformOrFail(
    raw => InviteCode(raw).toRight(s"Invalid invite code: $raw"),
    _.value
  )
  private given JsonCodec[LobbyStatus] = JsonCodec[String].transformOrFail(
    raw =>
      LobbyStatus.values
        .find(_.toString == raw)
        .toRight(s"Unknown lobby status: $raw"),
    _.toString
  )
  private[redis] given JsonCodec[Lobby] = DeriveJsonCodec.gen[Lobby]

  val layer: URLayer[Redis, LobbyRepository] =
    ZLayer.fromFunction(RedisLobbyRepository(_))

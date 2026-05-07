package chess.controller

import zio.*

/** In-memory map of `gameId -> ActivePlayers`. Tracks which session(s) are
  * permitted to mutate a given game; spectators (any other session) get
  * read-only access via SSE.
  *
  * For a local game (`registerLocal`) the creator's session is the only
  * permitted mover and self-play is allowed (the same session moves both
  * sides). For a lobbied game (Phase 2 `registerLobby`) the host and
  * guest each move their own colour, but for Phase 1 we just check
  * membership in a small set — colour-based gating arrives with the
  * lobby work.
  *
  * The registry is purely advisory at the HTTP layer: the gameService
  * authoritatively validates moves regardless. Its job is only to reject
  * spectator/anonymous mutations before they reach gRPC.
  */
trait SessionRegistry:
  def registerLocal(gameId: String, sessionId: String): UIO[Unit]
  def registerLobby(gameId: String, host: String, guest: String): UIO[Unit]
  def canMutate(gameId: String, sessionId: String): UIO[Boolean]

object SessionRegistry:

  final case class ActivePlayers(
      host: String,
      guest: Option[String],
      allowSelfPlay: Boolean
  ):
    def includes(sessionId: String): Boolean =
      host == sessionId || guest.contains(sessionId)

  def make: UIO[SessionRegistry] =
    Ref.make(Map.empty[String, ActivePlayers]).map(InMemorySessionRegistry(_))

private final class InMemorySessionRegistry(
    state: Ref[Map[String, SessionRegistry.ActivePlayers]]
) extends SessionRegistry:
  import SessionRegistry.ActivePlayers

  def registerLocal(gameId: String, sessionId: String): UIO[Unit] =
    state.update(_ + (gameId -> ActivePlayers(sessionId, None, true)))

  def registerLobby(gameId: String, host: String, guest: String): UIO[Unit] =
    state.update(_ + (gameId -> ActivePlayers(host, Some(guest), false)))

  def canMutate(gameId: String, sessionId: String): UIO[Boolean] =
    state.get.map(_.get(gameId).exists(_.includes(sessionId)))

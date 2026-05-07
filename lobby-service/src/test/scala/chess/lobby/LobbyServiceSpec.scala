package chess.lobby

import chess.model.{
  InviteCode,
  Lobby,
  LobbyError,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.{InMemoryLobbyRepository, LobbyRepository}
import zio.*
import zio.test.*

object LobbyServiceSpec extends ZIOSpecDefault:

  /** Test fake for the gateway hand-off — captures calls into a Ref so
    * tests can assert the right pair was registered.
    */
  private final class RecordingGateway(
      ref: Ref[List[(String, String, Option[String])]]
  ) extends GatewayCoordinator:
    def registerPlayers(
        gameId: String,
        hostSessionId: String,
        guestSessionId: Option[String]
    ): IO[Throwable, Unit] =
      ref.update(_ :+ ((gameId, hostSessionId, guestSessionId)))

  /** No-op gateway used by tests that don't care about the hand-off. */
  private val noopGatewayLayer: ULayer[GatewayCoordinator] =
    ZLayer.succeed(new GatewayCoordinator:
      def registerPlayers(
          gameId: String,
          hostSessionId: String,
          guestSessionId: Option[String]
      ): IO[Throwable, Unit] = ZIO.unit
    )

  private val appLayer: ULayer[LobbyService] =
    InMemoryLobbyRepository.layer ++ noopGatewayLayer >>> LobbyService.layer

  private val sampleInput = NewLobbyInput(
    hostNickname = "alice",
    hostSessionId = "session-host",
    visibility = LobbyVisibility.Public,
    allowUndo = true,
    allowSpectate = true,
    spectatorLimit = 8
  )

  def spec = suite("LobbyService")(
    suite("createLobby")(
      test("creates a lobby in Waiting state with a fresh invite code") {
        for lobby <- LobbyService.createLobby(sampleInput)
        yield assertTrue(
          lobby.hostNickname == "alice",
          lobby.hostSessionId == "session-host",
          lobby.guestNickname.isEmpty,
          lobby.guestSessionId.isEmpty,
          lobby.visibility == LobbyVisibility.Public,
          lobby.allowUndo == true,
          lobby.allowSpectate == true,
          lobby.spectatorLimit == 8,
          lobby.status == LobbyStatus.Waiting,
          lobby.gameId.isEmpty,
          lobby.inviteCode.value.length == InviteCode.Length
        )
      },
      test("trims the host nickname") {
        for lobby <- LobbyService.createLobby(
                       sampleInput.copy(hostNickname = "  alice  ")
                     )
        yield assertTrue(lobby.hostNickname == "alice")
      },
      test("rejects an empty host nickname") {
        for exit <- LobbyService
                      .createLobby(sampleInput.copy(hostNickname = "  "))
                      .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an empty host sessionId") {
        for exit <- LobbyService
                      .createLobby(sampleInput.copy(hostSessionId = ""))
                      .exit
        yield assertTrue(exit.isFailure)
      },
      test("persists the lobby so getLobby finds it") {
        for
          created <- LobbyService.createLobby(sampleInput)
          fetched <- LobbyService.getLobby(created.id)
        yield assertTrue(fetched.contains(created))
      }
    ),
    suite("joinLobby")(
      test("transitions Waiting → Full and records guest nickname + session") {
        for
          created <- LobbyService.createLobby(sampleInput)
          joined <- LobbyService.joinLobby(
                      created.inviteCode,
                      "bob",
                      "session-guest"
                    )
        yield assertTrue(
          joined.status == LobbyStatus.Full,
          joined.guestNickname.contains("bob"),
          joined.guestSessionId.contains("session-guest"),
          joined.id == created.id
        )
      },
      test("rejects a join with an unknown invite code") {
        for exit <- LobbyService
                      .joinLobby(
                        InviteCode.unsafe("MISSNG"),
                        "bob",
                        "session-guest"
                      )
                      .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an empty guest nickname") {
        for
          created <- LobbyService.createLobby(sampleInput)
          exit <- LobbyService
                    .joinLobby(created.inviteCode, "  ", "session-guest")
                    .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an empty guest sessionId") {
        for
          created <- LobbyService.createLobby(sampleInput)
          exit <- LobbyService
                    .joinLobby(created.inviteCode, "bob", "")
                    .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a join when the lobby is already Full") {
        for
          created <- LobbyService.createLobby(sampleInput)
          _ <- LobbyService.joinLobby(created.inviteCode, "bob", "session-bob")
          exit <- LobbyService
                    .joinLobby(created.inviteCode, "carol", "session-carol")
                    .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("startGame")(
      test("transitions Full → Started, pins gameId, and notifies gateway") {
        for
          ref      <- Ref.make(List.empty[(String, String, Option[String])])
          gw        = new RecordingGateway(ref)
          mapRef   <- Ref.make(Map.empty[chess.model.LobbyId, Lobby])
          repo      = InMemoryLobbyRepository(mapRef)
          svc       = LobbyServiceLive(repo, gw)
          created  <- svc.createLobby(sampleInput)
          _        <- svc.joinLobby(
                        created.inviteCode,
                        "bob",
                        "session-guest"
                      )
          started  <- svc.startGame(created.id, "game-42")
          recorded <- ref.get
        yield assertTrue(
          started.status == LobbyStatus.Started,
          started.gameId.contains("game-42"),
          recorded == List(("game-42", "session-host", Some("session-guest")))
        )
      },
      test("rejects a start when the lobby is still Waiting") {
        for
          created <- LobbyService.createLobby(sampleInput)
          exit    <- LobbyService.startGame(created.id, "game-1").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a start for an unknown lobby id") {
        for exit <- LobbyService.startGame("nope", "game-1").exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("listPublic")(
      test("returns only public + waiting lobbies") {
        val privateInput =
          sampleInput.copy(visibility = LobbyVisibility.Private)
        for
          publicLobby <- LobbyService.createLobby(sampleInput)
          _ <- LobbyService.createLobby(privateInput)
          rows <- LobbyService.listPublic()
        yield assertTrue(
          rows.exists(_.id == publicLobby.id),
          rows.size == 1
        )
      }
    ),
    suite("getLobby")(
      test("returns None for an unknown id") {
        for result <- LobbyService.getLobby("nope")
        yield assertTrue(result.isEmpty)
      }
    ),
    suite("closeLobby")(
      test("transitions any state to Closed") {
        for
          created <- LobbyService.createLobby(sampleInput)
          _       <- LobbyService.closeLobby(created.id)
          fetched <- LobbyService.getLobby(created.id)
        yield assertTrue(fetched.exists(_.status == LobbyStatus.Closed))
      },
      test("rejects close for an unknown lobby id") {
        for exit <- LobbyService.closeLobby("nope").exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("invite-code reservation")(
      test("retries on collision until a unique code is generated") {
        // CollidingLobbyRepo claims the first N invite codes collide,
        // then yields. The service must keep generating until it lands a
        // free one.
        val collisionsBeforeFree = 3
        for
          ref <- Ref.make(0)
          repo = CollidingLobbyRepo(ref, collisionsBeforeFree)
          gw   = new GatewayCoordinator:
            def registerPlayers(
                gameId: String,
                hostSessionId: String,
                guestSessionId: Option[String]
            ): IO[Throwable, Unit] = ZIO.unit
          svc  = LobbyServiceLive(repo, gw)
          lobby <- svc.createLobby(sampleInput)
          attempts <- ref.get
        yield assertTrue(
          lobby.hostNickname == "alice",
          attempts == collisionsBeforeFree + 1
        )
      },
      test("fails with InfrastructureError if every attempt collides") {
        for
          ref <- Ref.make(0)
          repo = CollidingLobbyRepo(ref, Int.MaxValue) // never frees
          gw   = new GatewayCoordinator:
            def registerPlayers(
                gameId: String,
                hostSessionId: String,
                guestSessionId: Option[String]
            ): IO[Throwable, Unit] = ZIO.unit
          svc  = LobbyServiceLive(repo, gw)
          exit <- svc.createLobby(sampleInput).exit
        yield assertTrue(
          exit.isFailure,
          exit.causeOption.exists { c =>
            c.failureOption.exists {
              case _: LobbyError.InfrastructureError => true
              case _                                 => false
            }
          }
        )
      }
    )
  ).provide(appLayer)

  /** Test fake: every `findByInviteCode` returns `Some` for the first
    * `collisionsBeforeFree` calls, then `None` afterwards. Counts attempts
    * via the supplied Ref so tests can assert how many tries the service
    * made.
    */
  private final class CollidingLobbyRepo(
      counter: Ref[Int],
      collisionsBeforeFree: Int
  ) extends LobbyRepository:
    override def create(lobby: Lobby) = ZIO.unit
    override def update(lobby: Lobby) = ZIO.unit
    override def delete(id: chess.model.LobbyId) = ZIO.unit
    override def findById(id: chess.model.LobbyId) = ZIO.none
    override def listPublicWaiting() = ZIO.succeed(Nil)
    override def findByInviteCode(code: InviteCode) =
      counter.getAndUpdate(_ + 1).map { n =>
        if n < collisionsBeforeFree then
          Some(makeStubLobby(code))
        else None
      }

    private def makeStubLobby(code: InviteCode): Lobby =
      Lobby(
        id = "stub",
        inviteCode = code,
        hostNickname = "stub",
        hostSessionId = "stub-session",
        guestNickname = None,
        guestSessionId = None,
        visibility = LobbyVisibility.Public,
        allowUndo = true,
        allowSpectate = true,
        spectatorLimit = 8,
        status = LobbyStatus.Waiting,
        createdAt = 0L,
        gameId = None
      )

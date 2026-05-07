package chess.lobby

import chess.model.{InviteCode, Lobby, LobbyError, LobbyStatus}
import chess.persistence.{InMemoryLobbyRepository, LobbyRepository}
import zio.*
import zio.test.*

object LobbyServiceSpec extends ZIOSpecDefault:

  private val appLayer: ULayer[LobbyService] =
    InMemoryLobbyRepository.layer >>> LobbyService.layer

  def spec = suite("LobbyService")(
    suite("createLobby")(
      test("creates a lobby in Waiting state with a fresh invite code") {
        for
          lobby <- LobbyService.createLobby("alice")
        yield assertTrue(
          lobby.hostNickname == "alice",
          lobby.guestNickname.isEmpty,
          lobby.status == LobbyStatus.Waiting,
          lobby.gameId.isEmpty,
          lobby.inviteCode.value.length == InviteCode.Length
        )
      },
      test("trims the host nickname") {
        for lobby <- LobbyService.createLobby("  alice  ")
        yield assertTrue(lobby.hostNickname == "alice")
      },
      test("rejects an empty host nickname") {
        for exit <- LobbyService.createLobby("   ").exit
        yield assertTrue(exit.isFailure)
      },
      test("persists the lobby so getLobby finds it") {
        for
          created <- LobbyService.createLobby("alice")
          fetched <- LobbyService.getLobby(created.id)
        yield assertTrue(fetched.contains(created))
      }
    ),
    suite("joinLobby")(
      test("transitions a Waiting lobby to Full and records the guest") {
        for
          created <- LobbyService.createLobby("alice")
          joined  <- LobbyService.joinLobby(created.inviteCode, "bob")
        yield assertTrue(
          joined.status == LobbyStatus.Full,
          joined.guestNickname.contains("bob"),
          joined.id == created.id
        )
      },
      test("rejects a join with an unknown invite code") {
        for exit <- LobbyService
                      .joinLobby(InviteCode.unsafe("MISSNG"), "bob")
                      .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an empty guest nickname") {
        for
          created <- LobbyService.createLobby("alice")
          exit    <- LobbyService.joinLobby(created.inviteCode, "  ").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a join when the lobby is already Full") {
        for
          created <- LobbyService.createLobby("alice")
          _       <- LobbyService.joinLobby(created.inviteCode, "bob")
          exit    <- LobbyService
                       .joinLobby(created.inviteCode, "carol")
                       .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("startGame")(
      test("transitions a Full lobby to Started with the game id") {
        for
          created <- LobbyService.createLobby("alice")
          _       <- LobbyService.joinLobby(created.inviteCode, "bob")
          started <- LobbyService.startGame(created.id, "game-42")
        yield assertTrue(
          started.status == LobbyStatus.Started,
          started.gameId.contains("game-42")
        )
      },
      test("rejects a start when the lobby is still Waiting") {
        for
          created <- LobbyService.createLobby("alice")
          exit    <- LobbyService.startGame(created.id, "game-1").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a start for an unknown lobby id") {
        for exit <- LobbyService.startGame("nope", "game-1").exit
        yield assertTrue(exit.isFailure)
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
          created <- LobbyService.createLobby("alice")
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
          svc  = LobbyServiceLive(repo)
          lobby <- svc.createLobby("alice")
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
          svc  = LobbyServiceLive(repo)
          exit <- svc.createLobby("alice").exit
        yield assertTrue(
          exit.isFailure,
          exit.causeOption.exists { c =>
            c.failureOption.exists {
              case _: LobbyError.InfrastructureError => true
              case _                                  => false
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
        guestNickname = None,
        status = LobbyStatus.Waiting,
        createdAt = 0L,
        gameId = None
      )

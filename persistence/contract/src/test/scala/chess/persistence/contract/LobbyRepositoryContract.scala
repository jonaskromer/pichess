package chess.persistence.contract

import chess.model.{InviteCode, Lobby, LobbyStatus}
import chess.persistence.LobbyRepository
import zio.*
import zio.test.*

abstract class LobbyRepositoryContract extends ZIOSpecDefault:

  def repoLayer: ZLayer[Any, Throwable, LobbyRepository]
  def label: String

  private val baseLobby = Lobby(
    id = "lobby-contract",
    inviteCode = InviteCode.unsafe("ABCDEF"),
    hostNickname = "alice",
    guestNickname = None,
    status = LobbyStatus.Waiting,
    createdAt = 1700000000000L,
    gameId = None
  )

  override final def spec =
    suite(s"LobbyRepository contract — $label")(
      test("findById returns None for an unknown id") {
        for result <- LobbyRepository.findById("missing")
        yield assertTrue(result.isEmpty)
      },
      test("findByInviteCode returns None for an unknown code") {
        for result <- LobbyRepository.findByInviteCode(
                        InviteCode.unsafe("MISSNG")
                      )
        yield assertTrue(result.isEmpty)
      },
      test("create then findById returns the lobby") {
        val l = baseLobby.copy(id = "create-1", inviteCode = InviteCode.unsafe("CREATA"))
        for
          _      <- LobbyRepository.create(l)
          result <- LobbyRepository.findById(l.id)
        yield assertTrue(result.contains(l))
      },
      test("findByInviteCode locates a created lobby") {
        val code = InviteCode.unsafe("INVCDE")
        val l = baseLobby.copy(id = "create-2", inviteCode = code)
        for
          _      <- LobbyRepository.create(l)
          result <- LobbyRepository.findByInviteCode(code)
        yield assertTrue(result.contains(l))
      },
      test("update overwrites prior state and preserves invite code") {
        val code = InviteCode.unsafe("UPDATX")
        val l = baseLobby.copy(id = "update-1", inviteCode = code)
        val joined = l.copy(
          guestNickname = Some("bob"),
          status = LobbyStatus.Full
        )
        for
          _       <- LobbyRepository.create(l)
          _       <- LobbyRepository.update(joined)
          byId    <- LobbyRepository.findById(l.id)
          byCode  <- LobbyRepository.findByInviteCode(code)
        yield assertTrue(
          byId.contains(joined),
          byCode.contains(joined)
        )
      },
      test("delete removes by id and clears invite-code lookup") {
        val code = InviteCode.unsafe("DELETZ")
        val l = baseLobby.copy(id = "delete-1", inviteCode = code)
        for
          _      <- LobbyRepository.create(l)
          _      <- LobbyRepository.delete(l.id)
          byId   <- LobbyRepository.findById(l.id)
          byCode <- LobbyRepository.findByInviteCode(code)
        yield assertTrue(byId.isEmpty, byCode.isEmpty)
      }
    ).provideShared(repoLayer)

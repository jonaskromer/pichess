package chess.repository

import zio.*
import zio.test.*

import chess.events.GameDomainEvent.*
import chess.model.{GameArchive, GameId}
import chess.opening.{EcoBook, EcoEntry}
import chess.persistence.{GameArchiveRepository, InMemoryGameArchiveRepository}

object GameArchiverSpec extends ZIOSpecDefault:

  private val eco = EcoBook.fromEntries(
    Vector(EcoEntry("B20", "Sicilian Defense", List("e4", "c5")))
  )

  private val mkRepo: UIO[GameArchiveRepository] =
    Ref.make(Map.empty[GameId, GameArchive]).map(InMemoryGameArchiveRepository(_))

  // ply0 white e4, ply1 black c5, ply2 white d4
  private val move0 = MoveMade("g", "p b - - 0 1", "e2e4", "e4", 1L)
  private val move1 = MoveMade("g", "p w - - 0 2", "c7c5", "c5", 2L)
  private val move2 = MoveMade("g", "p b - - 0 2", "d2d4", "d4", 3L)

  private def archiverWith(repo: GameArchiveRepository): UIO[GameArchiver] =
    GameArchiver.make(repo, eco)

  def spec = suite("GameArchiver")(
    test("accumulates moves idempotently; finalize saves the archive") {
      for
        repo     <- mkRepo
        archiver <- archiverWith(repo)
        _        <- archiver.handle(move0)
        _        <- archiver.handle(move1)
        _        <- archiver.handle(move0) // duplicate ply → overwrite
        _        <- archiver.handle(GameEnded("g", "p w - - 0 2", "Draw", 9L))
        archive  <- repo.find("g")
      yield assertTrue(
        archive.exists(_.plies.map(_.san) == List("e4", "c5")),
        archive.map(_.result) == Some("1/2-1/2"),
        archive.map(_.openingName) == Some("Sicilian Defense"),
        archive.exists(_.pgn.contains("1. e4 c5"))
      )
    },
    test("finalize truncates plies to the final position's ply count") {
      for
        repo     <- mkRepo
        archiver <- archiverWith(repo)
        _        <- ZIO.foreachDiscard(List(move0, move1, move2))(archiver.handle)
        _        <- archiver.handle(Forfeited("g", "p w - - 0 2", "Black", 9L))
        archive  <- repo.find("g")
      yield assertTrue(
        archive.exists(_.plies.map(_.ply) == List(0, 1)),
        archive.map(_.result) == Some("0-1")
      )
    },
    test("a malformed MoveMade is skipped; a game with no plies isn't saved") {
      for
        repo     <- mkRepo
        archiver <- archiverWith(repo)
        _        <- archiver.handle(MoveMade("g", "garbage", "e2e4", "e4", 1L))
        _        <- archiver.handle(GameEnded("g", "garbage", "Draw", 9L))
        archive  <- repo.find("g")
      yield assertTrue(archive == None)
    },
    test("DrawClaimed finalizes as a draw; non-terminal events are ignored") {
      for
        repo     <- mkRepo
        archiver <- archiverWith(repo)
        _        <- archiver.handle(GameStarted("g", "start", 0L))
        _        <- archiver.handle(Undone("g", "p b - - 0 1", 0L))
        _        <- archiver.handle(move0)
        _        <- archiver.handle(DrawClaimed("g", "p b - - 0 1", "agreement", 9L))
        archive  <- repo.find("g")
      yield assertTrue(archive.map(_.result) == Some("1/2-1/2"))
    },
    test("a malformed final FEN keeps all accumulated plies") {
      for
        repo     <- mkRepo
        archiver <- archiverWith(repo)
        _        <- archiver.handle(move0)
        _        <- archiver.handle(move1)
        _        <- archiver.handle(GameEnded("g", "garbage", "Draw", 9L))
        archive  <- repo.find("g")
      yield assertTrue(archive.exists(_.plies.length == 2))
    },
    test("a duplicate terminal event does not overwrite with an empty archive") {
      for
        repo     <- mkRepo
        archiver <- archiverWith(repo)
        _        <- archiver.handle(move0)
        _        <- archiver.handle(move1)
        _        <- archiver.handle(GameEnded("g", "p w - - 0 2", "Draw", 9L))
        _        <- archiver.handle(GameEnded("g", "p w - - 0 2", "Draw", 9L)) // dup
        archive  <- repo.find("g")
      yield assertTrue(archive.exists(_.plies.length == 2))
    }
  )

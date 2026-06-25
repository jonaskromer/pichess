package chess.repository

import zio.test.*

import chess.events.GameDomainEvent.*

object ArchiveProjectionSpec extends ZIOSpecDefault:

  def spec = suite("ArchiveProjection")(
    test("plyIndex / plyCount from side-to-move + fullmove") {
      assertTrue(
        ArchiveProjection.plyIndex("p b - - 0 1") == Some(0),
        ArchiveProjection.plyIndex("p w - - 0 2") == Some(1),
        ArchiveProjection.plyIndex("p b - - 0 2") == Some(2),
        ArchiveProjection.plyCount("p w - - 0 2") == Some(2)
      )
    },
    test("plyIndex rejects malformed FENs") {
      assertTrue(
        ArchiveProjection.plyIndex("p b - -") == None,        // too few fields
        ArchiveProjection.plyIndex("p z - - 0 1") == None,    // bad side
        ArchiveProjection.plyIndex("p b - - 0 x") == None,    // bad fullmove
        ArchiveProjection.plyIndex("p b - - 0 0") == None,    // fullmove < 1
        ArchiveProjection.plyCount("bad") == None
      )
    },
    test("mover is the opposite of side-to-move") {
      assertTrue(
        ArchiveProjection.mover("p w - - 0 1") == Some("black"),
        ArchiveProjection.mover("p b - - 0 1") == Some("white"),
        ArchiveProjection.mover("p z - - 0 1") == None,
        ArchiveProjection.mover("p") == None
      )
    },
    test("plyOf builds a row, or None for a malformed FEN") {
      val ok = MoveMade("g", "p b - - 0 1", "e2e4", "e4", 7L)
      val bad = MoveMade("g", "nope", "e2e4", "e4", 7L)
      assertTrue(
        ArchiveProjection.plyOf(ok).contains(
          chess.model.ArchivePly(0, "white", "e4", "e2e4", "p b - - 0 1", 7L, None, None)
        ),
        ArchiveProjection.plyOf(bad) == None
      )
    },
    test("resultToken maps terminal events to PGN tokens") {
      assertTrue(
        ArchiveProjection.resultToken(GameEnded("g", "p w - - 0 2", "Draw", 0L)) == "1/2-1/2",
        ArchiveProjection.resultToken(GameEnded("g", "p b - - 0 2", "Checkmate", 0L)) == "1-0",
        ArchiveProjection.resultToken(GameEnded("g", "p w - - 0 2", "Checkmate", 0L)) == "0-1",
        ArchiveProjection.resultToken(GameEnded("g", "bad", "Checkmate", 0L)) == "*",
        ArchiveProjection.resultToken(Forfeited("g", "f", "White", 0L)) == "1-0",
        ArchiveProjection.resultToken(Forfeited("g", "f", "Black", 0L)) == "0-1",
        ArchiveProjection.resultToken(Forfeited("g", "f", "", 0L)) == "*",
        ArchiveProjection.resultToken(DrawClaimed("g", "f", "agreement", 0L)) == "1/2-1/2",
        ArchiveProjection.resultToken(GameStarted("g", "f", 0L)) == "*"
      )
    }
  )

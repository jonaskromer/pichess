package chess.bot.engine.internal

import zio.*
import zio.test.*

import chess.codec.FenParserRegex
import chess.model.board.{BoardLike, GameState, MoveInt}
import chess.model.rules.Game

/** Equivalence proof for the copy-make apply: [[SearchPos.copyMakeInto]]
  * must reproduce `Game.applyMoveCoreSync` exactly — same legality
  * verdict and, when legal, the same board bitboards + side-to-move +
  * en-passant target + castling rights + halfmove clock.
  *
  * Cross-checks every legal move (under-promotions ON) to a fixed depth
  * over the standard perft positions, so castling, en passant (set then
  * captured), promotion incl. under-promotion, captures, pins and the
  * rights/clock bookkeeping are all exercised against the trusted
  * immutable apply. Companion to `PerftSpec` (which proves the same
  * equivalence at the leaf-count level). */
object SearchPosSpec extends ZIOSpecDefault:

  private def boardsEqual(mb: BoardLike, bs: BoardLike): Boolean =
    mb.pawnsW.raw == bs.pawnsW.raw && mb.knightsW.raw == bs.knightsW.raw &&
      mb.bishopsW.raw == bs.bishopsW.raw && mb.rooksW.raw == bs.rooksW.raw &&
      mb.queensW.raw == bs.queensW.raw && mb.kingW.raw == bs.kingW.raw &&
      mb.pawnsB.raw == bs.pawnsB.raw && mb.knightsB.raw == bs.knightsB.raw &&
      mb.bishopsB.raw == bs.bishopsB.raw && mb.rooksB.raw == bs.rooksB.raw &&
      mb.queensB.raw == bs.queensB.raw && mb.kingB.raw == bs.kingB.raw &&
      mb.whitePieces.raw == bs.whitePieces.raw &&
      mb.blackPieces.raw == bs.blackPieces.raw &&
      mb.occupancy.raw == bs.occupancy.raw

  /** True when copy-make agrees with `applyMoveCoreSync` for every move
    * in the tree under `state` to `depth`. Recurses on the immutable
    * result; since copy-make is proven to match it on every field
    * copy-make reads, that's a sound induction. */
  private def crossCheck(state: GameState, depth: Int): Boolean =
    if depth == 0 then true
    else
      val cap    = new Array[Int](256)
      val quiet  = new Array[Int](256)
      val packed = RulesAdapter.fillCapturesAndQuiets(state, cap, quiet, underPromotion = true)
      val capCount   = (packed >>> 32).toInt
      val quietCount = packed.toInt
      val parent = new SearchPos
      parent.setFrom(state)
      val child = new SearchPos
      var ok = true

      def checkMove(m: Int): Unit =
        if ok then
          val legal = parent.copyMakeInto(child, m)
          val gm    = Game.applyMoveCoreSync(state, MoveInt.decode(m))
          if legal != gm.isDefined then ok = false
          else
            gm match
              case Some(next) =>
                val same =
                  boardsEqual(child.board, next.board) &&
                    child.activeColor == next.activeColor &&
                    child.enPassantTarget == next.enPassantTarget &&
                    child.castlingRights == next.castlingRights &&
                    child.halfmoveClock == next.halfmoveClock
                if !same then ok = false
                else if !crossCheck(next, depth - 1) then ok = false
              case None => ()

      var i = 0
      while i < capCount do { checkMove(cap(i)); i += 1 }
      i = 0
      while i < quietCount do { checkMove(quiet(i)); i += 1 }
      ok

  private def equivTest(name: String, fen: String, depth: Int) =
    test(name) {
      for state <- FenParserRegex.parse(fen)
      yield assertTrue(crossCheck(state, depth))
    }

  def spec = suite("SearchPos.copyMakeInto ≡ Game.applyMoveCoreSync")(
    equivTest(
      "startposition (depth 3)",
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      3,
    ),
    equivTest(
      "Kiwipete — castling + EP + captures (depth 2)",
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      2,
    ),
    equivTest(
      "position 3 — EP discoveries + checks (depth 3)",
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
      3,
    ),
    equivTest(
      "position 4 — promotions + pins (depth 2)",
      "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
      2,
    ),
    equivTest(
      "position 5 — under-promotion + cramped (depth 3)",
      "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
      3,
    ),
  )

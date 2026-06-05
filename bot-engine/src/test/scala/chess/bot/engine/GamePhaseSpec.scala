package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

object GamePhaseSpec extends ZIOSpecDefault:

  private def phaseOf(fen: String) =
    FenParserRegex.parse(fen).map(GamePhase.compute)

  def spec = suite("GamePhase.compute")(
    test("starting position is fully opening (phase = 1.0)") {
      for p <- phaseOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      yield assertTrue(p == 1.0)
    },
    test("bare-kings position is fully endgame (phase = 0.0)") {
      for p <- phaseOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
      yield assertTrue(p == 0.0)
    },
    test("kings + pawns is fully endgame (phase = 0.0) — pawns don't count") {
      // Phase weights are 0 for pawns by definition, so a king-and-pawn
      // ending is just as much an endgame as bare kings as far as the
      // game-phase blend is concerned.
      for p <- phaseOf("4k3/pppppppp/8/8/8/8/PPPPPPPP/4K3 w - - 0 1")
      yield assertTrue(p == 0.0)
    },
    test("position with only one side's queen + rook is a partial endgame") {
      // White has Q + R; black has K only. raw = 4 + 2 = 6.
      // phase = 6 / 24 = 0.25.
      for p <- phaseOf("4k3/8/8/8/8/8/8/3QK2R w K - 0 1")
      yield assertTrue(math.abs(p - 0.25) < 1e-9)
    },
    test("middlegame with all minor pieces but no queens is mid-phase") {
      // Each side has 2 knights + 2 bishops + 2 rooks. raw = (2·1 + 2·1 + 2·2) × 2 = 16.
      // phase = 16 / 24 ≈ 0.667
      for p <- phaseOf("rnbk1bnr/pppppppp/8/8/8/8/PPPPPPPP/RNBK1BNR w - - 0 1")
      yield assertTrue(math.abs(p - 16.0 / 24.0) < 1e-9)
    },
    test("over-max raw (e.g. multiple queens after promotion) clamps to 1.0") {
      // Three white queens (one original + two promoted) plus normal black material.
      // raw = (3·4 + 1·4) + ... > 24. Should clamp to 1.0.
      for p <- phaseOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RQBQKBQR w KQkq - 0 1")
      yield assertTrue(p == 1.0)
    },
    test("compute(state) and compute(state.board) agree") {
      for state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
      yield assertTrue(GamePhase.compute(state) == GamePhase.compute(state.board))
    },
  )

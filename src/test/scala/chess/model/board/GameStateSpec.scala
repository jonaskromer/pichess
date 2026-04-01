package chess.model.board

import chess.model.piece.Color
import zio.test.*

object GameStateSpec extends ZIOSpecDefault:

  def spec = suite("GameState.initial")(
    test("set White as the active color") {
      assertTrue(GameState.initial.activeColor == Color.White)
    },
    test("use the standard initial board") {
      assertTrue(GameState.initial.board == Board.initial)
    },
    test("have no en passant target initially") {
      assertTrue(GameState.initial.enPassantTarget == None)
    }
  )

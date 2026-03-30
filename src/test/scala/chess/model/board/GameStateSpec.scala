package chess.model.board

import chess.model.piece.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameStateSpec extends AnyFlatSpec with Matchers:

  "GameState.initial" should "set White as the active color" in:
    GameState.initial.activeColor shouldBe Color.White

  it should "use the standard initial board" in:
    GameState.initial.board shouldBe Board.initial

  it should "have no en passant target initially" in:
    GameState.initial.enPassantTarget shouldBe None

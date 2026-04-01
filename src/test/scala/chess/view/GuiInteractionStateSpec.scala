package chess.view

import chess.model.board.Position
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuiInteractionStateSpec extends AnyFlatSpec with Matchers:

  "GuiInteractionState" should "transition to Selected when idle and a piece is clicked" in {
    val startPos = Position('e', 2)
    val (nextState, moveOpt) =
      GuiInteractionState.handleClick(GuiInteractionState.Idle, startPos)

    nextState shouldBe GuiInteractionState.Selected(startPos)
    moveOpt shouldBe None
  }

  it should "return an optional move string and transition to Idle when a destination is clicked" in {
    val startPos = Position('e', 2)
    val endPos = Position('e', 4)

    val (nextState, moveOpt) = GuiInteractionState.handleClick(
      GuiInteractionState.Selected(startPos),
      endPos
    )

    nextState shouldBe GuiInteractionState.Idle
    moveOpt shouldBe Some("e2e4")
  }

  it should "deselect and transition to Idle if the same square is clicked twice" in {
    val pos = Position('e', 2)

    val (nextState, moveOpt) =
      GuiInteractionState.handleClick(GuiInteractionState.Selected(pos), pos)

    nextState shouldBe GuiInteractionState.Idle
    moveOpt shouldBe None
  }

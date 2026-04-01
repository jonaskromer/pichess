package chess.view

import chess.model.piece.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveLogViewSpec extends AnyFlatSpec with Matchers:

  "MoveLogView.render" should "return empty string for empty log" in:
    MoveLogView.render(Nil) shouldBe ""

  it should "render a single white move" in:
    val result = MoveLogView.render(List((Color.White, "e4")))
    result should include("White")
    result should include("e4")

  it should "render a single black move" in:
    val result = MoveLogView.render(List((Color.Black, "e5")))
    result should include("Black")
    result should include("e5")

  it should "render only the last two moves connected by an arrow" in:
    val log =
      List((Color.White, "e4"), (Color.Black, "e5"), (Color.White, "Nf3"))
    val result = MoveLogView.render(log)
    result should include("e5")
    result should include("->")
    result should include("Nf3")
    result should not include "e4"

  it should "contain ANSI styling for White label" in:
    val result = MoveLogView.render(List((Color.White, "e4")))
    result should include("\u001b[30;47m")
    result should include("\u001b[0m")

  it should "contain ANSI styling for Black label" in:
    val result = MoveLogView.render(List((Color.Black, "d5")))
    result should include("\u001b[97;40m")
    result should include("\u001b[0m")

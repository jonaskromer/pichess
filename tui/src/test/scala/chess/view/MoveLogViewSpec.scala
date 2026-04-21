package chess.view

import chess.model.piece.Color
import zio.test.*

object MoveLogViewSpec extends ZIOSpecDefault:

  def spec = suite("MoveLogView.render")(
    test("return empty string for empty log") {
      assertTrue(MoveLogView.render(Nil) == "")
    },
    test("render a single white move") {
      val result = MoveLogView.render(List((Color.White, "e4")))
      assertTrue(result.contains("White"), result.contains("e4"))
    },
    test("render a single black move") {
      val result = MoveLogView.render(List((Color.Black, "e5")))
      assertTrue(result.contains("Black"), result.contains("e5"))
    },
    test("render only the last two moves connected by an arrow") {
      val log =
        List((Color.White, "e4"), (Color.Black, "e5"), (Color.White, "Nf3"))
      val result = MoveLogView.render(log)
      assertTrue(
        result.contains("e5"),
        result.contains("->"),
        result.contains("Nf3"),
        !result.contains("e4")
      )
    },
    test("contain ANSI styling for White label") {
      val result = MoveLogView.render(List((Color.White, "e4")))
      assertTrue(
        result.contains("\u001b[30;47m"),
        result.contains("\u001b[0m")
      )
    },
    test("contain ANSI styling for Black label") {
      val result = MoveLogView.render(List((Color.Black, "d5")))
      assertTrue(
        result.contains("\u001b[97;40m"),
        result.contains("\u001b[0m")
      )
    }
  )

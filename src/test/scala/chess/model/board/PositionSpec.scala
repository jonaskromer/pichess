package chess.model.board

import chess.model.GameError
import zio.ZIO
import zio.test.*

/** Contract tests for [[Position.make]], the validated factory that is the
  * only way to construct a Position from untrusted coordinates.
  */
object PositionSpec extends ZIOSpecDefault:

  def spec = suite("Position.make")(
    suite("valid coordinates succeed")(
      test("construct a1 (lower-left corner)") {
        for pos <- Position.make('a', 1)
        yield assertTrue(pos.col == 'a', pos.row == 1)
      },
      test("construct h8 (upper-right corner)") {
        for pos <- Position.make('h', 8)
        yield assertTrue(pos.col == 'h', pos.row == 8)
      },
      test("construct a central square e4") {
        for pos <- Position.make('e', 4)
        yield assertTrue(pos.col == 'e', pos.row == 4)
      },
      test("round-trip via toString") {
        for pos <- Position.make('c', 6)
        yield assertTrue(pos.toString == "c6")
      }
    ),
    suite("invalid coordinates fail with ParseError")(
      test("column before 'a' fails") {
        for exit <- Position.make('`', 4).exit
        yield assertTrue(exit.isFailure)
      },
      test("column after 'h' fails") {
        for err <- Position.make('i', 4).flip
        yield assertTrue(
          err.isInstanceOf[GameError.ParseError],
          err.message.contains("i4")
        )
      },
      test("row 0 fails") {
        for err <- Position.make('e', 0).flip
        yield assertTrue(
          err.isInstanceOf[GameError.ParseError],
          err.message.contains("e0")
        )
      },
      test("row 9 fails") {
        for err <- Position.make('e', 9).flip
        yield assertTrue(
          err.isInstanceOf[GameError.ParseError],
          err.message.contains("e9")
        )
      },
      test("far-out-of-range values fail") {
        for exit <- Position.make('z', 99).exit
        yield assertTrue(exit.isFailure)
      },
      test("negative row fails") {
        for exit <- Position.make('a', -1).exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("structural equality")(
      test("two positions with the same coordinates are equal") {
        for
          a <- Position.make('d', 4)
          b <- Position.make('d', 4)
        yield assertTrue(a == b, a.hashCode == b.hashCode)
      },
      test("two positions with different coordinates are not equal") {
        for
          a <- Position.make('d', 4)
          b <- Position.make('d', 5)
        yield assertTrue(a != b)
      }
    )
  )

package chess.codec

import chess.model.GameError
import zio.test.*

/** Direct unit tests for [[FenBuilder]].
  *
  * Each [[FenParser]] tokenizes its input through a strict grammar before
  * handing the six raw fields to [[FenBuilder]]. That means a few defensive
  * validation branches inside [[FenBuilder]] cannot be reached through any of
  * the three parser front-ends, even though they are required for the builder
  * to be safe when called directly. This spec exercises those branches by
  * invoking [[FenBuilder.build]] with synthetic field strings that no parser
  * would ever produce.
  */
object FenBuilderSpec extends ZIOSpecDefault:

  private val initial = (
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
    "w",
    "KQkq",
    "-",
    "0",
    "1"
  )

  def spec = suite("FenBuilder")(
    test("rejects an active color that is neither 'w' nor 'b'") {
      for err <- FenBuilder
          .build(
            initial._1,
            "x",
            initial._3,
            initial._4,
            initial._5,
            initial._6
          )
          .flip
      yield assertTrue(
        err == GameError.ParseError(
          "Invalid active color 'x' (expected 'w' or 'b')"
        )
      )
    },
    test("rejects an en passant target that is not '-' or a valid square") {
      for err <- FenBuilder
          .build(
            initial._1,
            initial._2,
            initial._3,
            "zz",
            initial._5,
            initial._6
          )
          .flip
      yield assertTrue(
        err == GameError.ParseError("Invalid en passant target square 'zz'")
      )
    },
    test("rejects a halfmove clock that overflows Int") {
      val huge = "9" * 20
      for err <- FenBuilder
          .build(
            initial._1,
            initial._2,
            initial._3,
            initial._4,
            huge,
            initial._6
          )
          .flip
      yield assertTrue(
        err == GameError.ParseError(s"Invalid halfmove clock '$huge'")
      )
    },
    test("rejects a fullmove number that overflows Int") {
      val huge = "9" * 20
      for err <- FenBuilder
          .build(
            initial._1,
            initial._2,
            initial._3,
            initial._4,
            initial._5,
            huge
          )
          .flip
      yield assertTrue(
        err == GameError.ParseError(s"Invalid fullmove number '$huge'")
      )
    },
    test("accepts the standard initial position when called directly") {
      for state <- FenBuilder
          .build(
            initial._1,
            initial._2,
            initial._3,
            initial._4,
            initial._5,
            initial._6
          )
      yield assertTrue(state.board.size == 32)
    },
    test("rejects a placement with an invalid piece character") {
      val placement = "rnbqkbnr/pppppppp/8/8/8/8/PPPPxxPP/RNBQKBNR"
      for exit <- FenBuilder.build(placement, "w", "KQkq", "-", "0", "1").exit
      yield assertTrue(exit.isFailure)
    }
  )

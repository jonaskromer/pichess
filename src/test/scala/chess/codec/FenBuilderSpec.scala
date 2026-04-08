package chess.codec

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
      val result =
        FenBuilder.build(
          initial._1,
          "x",
          initial._3,
          initial._4,
          initial._5,
          initial._6
        )
      assertTrue(result == Left("Invalid active color 'x' (expected 'w' or 'b')"))
    },
    test("rejects an en passant target that is not '-' or a valid square") {
      val result =
        FenBuilder.build(
          initial._1,
          initial._2,
          initial._3,
          "zz",
          initial._5,
          initial._6
        )
      assertTrue(result == Left("Invalid en passant target square 'zz'"))
    },
    test("rejects a halfmove clock that overflows Int") {
      val huge = "9" * 20
      val result =
        FenBuilder.build(
          initial._1,
          initial._2,
          initial._3,
          initial._4,
          huge,
          initial._6
        )
      assertTrue(result == Left(s"Invalid halfmove clock '$huge'"))
    },
    test("rejects a fullmove number that overflows Int") {
      val huge = "9" * 20
      val result =
        FenBuilder.build(
          initial._1,
          initial._2,
          initial._3,
          initial._4,
          initial._5,
          huge
        )
      assertTrue(result == Left(s"Invalid fullmove number '$huge'"))
    },
    test("accepts the standard initial position when called directly") {
      val result =
        FenBuilder.build(
          initial._1,
          initial._2,
          initial._3,
          initial._4,
          initial._5,
          initial._6
        )
      assertTrue(result.isRight)
    },
    test("rejects a placement with an invalid piece character") {
      // Two invalid chars in the same rank exercise both the first failure
      // (Some(piece)/None branch) and the early-exit branch on the next
      // fold iteration once the accumulator is already Left.
      val placement = "rnbqkbnr/pppppppp/8/8/8/8/PPPPxxPP/RNBQKBNR"
      val result =
        FenBuilder.build(placement, "w", "KQkq", "-", "0", "1")
      assertTrue(result.isLeft)
    }
  )

package chess.model.piece

import zio.test.*

object ColorSpec extends ZIOSpecDefault:

  def spec = suite("Color")(
    test("Color.White.opposite should be Black") {
      assertTrue(Color.White.opposite == Color.Black)
    },
    test("Color.Black.opposite should be White") {
      assertTrue(Color.Black.opposite == Color.White)
    }
  )

package chess.model.piece

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ColorSpec extends AnyFlatSpec with Matchers:

  "Color.White.opposite" should "be Black" in:
    Color.White.opposite shouldBe Color.Black

  "Color.Black.opposite" should "be White" in:
    Color.Black.opposite shouldBe Color.White

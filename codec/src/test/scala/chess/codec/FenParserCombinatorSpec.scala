package chess.codec

import zio.test.*

object FenParserCombinatorSpec extends ZIOSpecDefault:

  def spec = suite("FenParserCombinator")(
    FenParserBehaviors.behaviors(FenParserCombinator)
  )

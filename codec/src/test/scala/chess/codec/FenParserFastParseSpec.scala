package chess.codec

import zio.test.*

object FenParserFastParseSpec extends ZIOSpecDefault:

  def spec = suite("FenParserFastParse")(
    FenParserBehaviors.behaviors(FenParserFastParse)
  )

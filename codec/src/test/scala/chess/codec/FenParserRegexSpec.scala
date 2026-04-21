package chess.codec

import zio.test.*

object FenParserRegexSpec extends ZIOSpecDefault:

  def spec = suite("FenParserRegex")(
    FenParserBehaviors.behaviors(FenParserRegex)
  )

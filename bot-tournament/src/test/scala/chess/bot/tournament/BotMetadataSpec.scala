package chess.bot.tournament

import zio.test.*

/** Pins piChess's bot-registry self-description so the metadata it advertises to
  * the tournament server (and thus analytics-export) doesn't drift silently. */
object BotMetadataSpec extends ZIOSpecDefault:

  def spec = suite("BotMetadata.pichess")(
    test("describes an alpha-beta NNUE+HCE hybrid, stamped with the version") {
      val m = BotMetadata.pichess(weightsVersion = 1)
      assertTrue(
        m.family == "piChess",
        m.strategyType == "alpha-beta",
        m.engineType == "NNUE+HCE hybrid",
        m.modelVersion == "weights-v1+nnue-v1"
      )
    }
  )

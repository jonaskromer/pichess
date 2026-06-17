package chess.bot.engine

import zio.json.*
import zio.test.*

import chess.model.piece.Color

object BotConfigSpec extends ZIOSpecDefault:

  def spec = suite("BotConfig")(
    test("round-trips through JSON with all three fields") {
      val cfg = BotConfig(
        botSide = Color.Black,
        difficulty = Difficulty.Hard,
        allowUndo = false
      )
      val json = cfg.toJson
      assertTrue(
        json.fromJson[BotConfig] == Right(cfg)
      )
    },
    test("decodes a hand-written JSON sample (lower-case color)") {
      val sample =
        """{ "botSide": "white",
          |  "difficulty": "Easy",
          |  "allowUndo": true }""".stripMargin
      assertTrue(
        sample.fromJson[BotConfig] ==
          Right(BotConfig(Color.White, Difficulty.Easy, allowUndo = true))
      )
    },
    test("rejects an unknown color") {
      val sample =
        """{ "botSide": "purple",
          |  "difficulty": "Easy",
          |  "allowUndo": true }""".stripMargin
      assertTrue(sample.fromJson[BotConfig].isLeft)
    },
    test("rejects missing fields") {
      val sample = """{ "botSide": "white" }"""
      assertTrue(sample.fromJson[BotConfig].isLeft)
    }
  )

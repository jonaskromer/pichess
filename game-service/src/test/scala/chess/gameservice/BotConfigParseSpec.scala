package chess.gameservice

import pichess.game_service.{LoadGameRequest, NewGameRequest}
import zio.test.*

import chess.bot.engine.{BotConfig, Difficulty}
import chess.model.piece.Color

/** Direct unit tests for [[GrpcMappers.parseBotConfig]] — the wire-format
  * validation gatekeeping vs-bot game creation.
  */
object BotConfigParseSpec extends ZIOSpecDefault:

  def spec = suite("GrpcMappers.parseBotConfig")(
    test("returns None when vs_bot is false (default proto3 values)") {
      val req = NewGameRequest() // all defaults: vsBot=false, empty strings
      assertTrue(GrpcMappers.parseBotConfig(req) == Right(None))
    },
    test("parses a well-formed vsBot=true request") {
      val req = NewGameRequest(
        vsBot = true,
        botSide = "white",
        botDifficulty = "Hard",
        allowUndo = false
      )
      assertTrue(
        GrpcMappers.parseBotConfig(req) == Right(
          Some(
            BotConfig(
              botSide = Color.White,
              difficulty = Difficulty.Hard,
              allowUndo = false
            )
          )
        )
      )
    },
    test("accepts case-insensitive side and difficulty") {
      val req = NewGameRequest(
        vsBot = true,
        botSide = "BLACK",
        botDifficulty = "expert",
        allowUndo = true
      )
      assertTrue(
        GrpcMappers.parseBotConfig(req) == Right(
          Some(
            BotConfig(
              Color.Black,
              Difficulty.Expert,
              allowUndo = true
            )
          )
        )
      )
    },
    test("rejects an unknown bot side") {
      val req = NewGameRequest(
        vsBot = true,
        botSide = "rainbow",
        botDifficulty = "Medium"
      )
      assertTrue(GrpcMappers.parseBotConfig(req).isLeft)
    },
    test("rejects an unknown difficulty") {
      val req = NewGameRequest(
        vsBot = true,
        botSide = "white",
        botDifficulty = "Impossible"
      )
      assertTrue(GrpcMappers.parseBotConfig(req).isLeft)
    },
    test("parses the same bot fields from a LoadGameRequest (load + vsBot)") {
      val req = LoadGameRequest(
        raw = "fen-here",
        vsBot = true,
        botSide = "black",
        botDifficulty = "Max",
        allowUndo = true
      )
      assertTrue(
        GrpcMappers.parseBotConfig(req) == Right(
          Some(BotConfig(Color.Black, Difficulty.Max, allowUndo = true))
        )
      )
    }
  )

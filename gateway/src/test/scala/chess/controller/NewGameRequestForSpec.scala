package chess.controller

import zio.test.*

import chess.api.VsBotSettings

object NewGameRequestForSpec extends ZIOSpecDefault:

  def spec = suite("WebController.newGameRequestFor")(
    test("returns default NewGameRequest when no vsBot settings supplied") {
      val req = WebController.newGameRequestFor(None)
      assertTrue(
        !req.vsBot,
        req.botSide.isEmpty,
        req.botDifficulty.isEmpty,
        !req.allowUndo,
      )
    },
    test("forwards vsBot settings into the gRPC request") {
      val req = WebController.newGameRequestFor(
        Some(VsBotSettings(botSide = "black", difficulty = "Hard", allowUndo = true))
      )
      assertTrue(
        req.vsBot,
        req.botSide == "black",
        req.botDifficulty == "Hard",
        req.allowUndo == true,
      )
    },
  )

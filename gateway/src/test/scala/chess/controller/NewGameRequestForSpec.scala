package chess.controller

import zio.test.*

import chess.api.{CreateGameRequest, VsBotSettings}

object NewGameRequestForSpec extends ZIOSpecDefault:

  def spec = suite("WebController.newGameRequestFor")(
    test("returns default NewGameRequest when no vsBot settings supplied") {
      val req = WebController.newGameRequestFor(CreateGameRequest())
      assertTrue(
        !req.vsBot,
        req.botSide.isEmpty,
        req.botDifficulty.isEmpty,
        !req.allowUndo,
        req.initialSeconds == 0,
        req.incrementSeconds == 0
      )
    },
    test("forwards vsBot settings into the gRPC request") {
      val req = WebController.newGameRequestFor(
        CreateGameRequest(vsBot =
          Some(
            VsBotSettings(
              botSide = "black",
              difficulty = "Hard",
              allowUndo = true
            )
          )
        )
      )
      assertTrue(
        req.vsBot,
        req.botSide == "black",
        req.botDifficulty == "Hard",
        req.allowUndo == true
      )
    },
    test("carries the clock onto a PvP (non-bot) timed game") {
      val req = WebController.newGameRequestFor(
        CreateGameRequest(initialSeconds = 300, incrementSeconds = 2)
      )
      assertTrue(
        !req.vsBot,
        req.initialSeconds == 300,
        req.incrementSeconds == 2
      )
    },
    test("carries the clock onto a timed vs-bot game") {
      val req = WebController.newGameRequestFor(
        CreateGameRequest(
          vsBot = Some(
            VsBotSettings(botSide = "white", difficulty = "Easy", allowUndo = false)
          ),
          initialSeconds = 600,
          incrementSeconds = 0
        )
      )
      assertTrue(
        req.vsBot,
        req.botSide == "white",
        req.initialSeconds == 600,
        req.incrementSeconds == 0
      )
    }
  )

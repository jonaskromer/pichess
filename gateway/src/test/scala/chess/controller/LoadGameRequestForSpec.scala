package chess.controller

import zio.test.*

import chess.api.{CreateGameRequest, VsBotSettings}

object LoadGameRequestForSpec extends ZIOSpecDefault:

  private val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def spec = suite("WebController.loadGameRequestFor")(
    test("carries the position with no bot fields when vsBot is absent") {
      val req =
        WebController.loadGameRequestFor(fen, CreateGameRequest(load = Some(fen)))
      assertTrue(
        req.raw == fen,
        !req.vsBot,
        req.botSide.isEmpty,
        req.botDifficulty.isEmpty,
        !req.allowUndo
      )
    },
    test("forwards vsBot settings alongside the position (the bot-drop fix)") {
      val req = WebController.loadGameRequestFor(
        fen,
        CreateGameRequest(
          load = Some(fen),
          vsBot = Some(
            VsBotSettings(botSide = "black", difficulty = "Max", allowUndo = true)
          )
        )
      )
      assertTrue(
        req.raw == fen,
        req.vsBot,
        req.botSide == "black",
        req.botDifficulty == "Max",
        req.allowUndo == true
      )
    }
  )

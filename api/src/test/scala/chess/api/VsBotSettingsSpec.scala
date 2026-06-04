package chess.api

import zio.json.*
import zio.test.*

object VsBotSettingsSpec extends ZIOSpecDefault:

  def spec = suite("VsBotSettings + CreateGameRequest vsBot field")(
    test("round-trips a vsBot CreateGameRequest through JSON") {
      val req = CreateGameRequest(
        load = None,
        vsBot = Some(VsBotSettings(
          botSide = "black", difficulty = "Hard", allowUndo = true,
        )),
      )
      val json = req.toJson
      assertTrue(
        json.fromJson[CreateGameRequest] == Right(req)
      )
    },
    test("decodes a hand-written 'no bot' request (backward compat)") {
      val sample = """{}"""
      assertTrue(
        sample.fromJson[CreateGameRequest] ==
          Right(CreateGameRequest(load = None, vsBot = None))
      )
    },
    test("decodes a load-only request") {
      val sample = """{"load": "1. e4 e5 *"}"""
      assertTrue(
        sample.fromJson[CreateGameRequest] ==
          Right(CreateGameRequest(load = Some("1. e4 e5 *"), vsBot = None))
      )
    },
    test("decodes a vsBot-only request") {
      val sample =
        """{ "vsBot": { "botSide": "white",
          |             "difficulty": "Medium",
          |             "allowUndo": false } }""".stripMargin
      assertTrue(
        sample.fromJson[CreateGameRequest] ==
          Right(CreateGameRequest(
            load = None,
            vsBot = Some(VsBotSettings(
              botSide = "white", difficulty = "Medium", allowUndo = false,
            )),
          ))
      )
    },
  )

package chess.bot.engine

import zio.json.*
import zio.test.*

object WeightSnapshotSpec extends ZIOSpecDefault:

  def spec = suite("WeightSnapshot JSON codec")(
    test("round-trips through JSON") {
      val snap = WeightSnapshot(
        version = 7,
        weights = Map("pawn" -> 100, "knight" -> 320, "queen" -> 900)
      )
      val json = snap.toJson
      val out = json.fromJson[WeightSnapshot]
      assertTrue(out == Right(snap))
    },
    test("parses a hand-written JSON sample (the v1 default shape)") {
      val sample =
        """{ "version": 1,
          |  "weights": { "pawn": 100, "knight": 320, "bishop": 330,
          |               "rook": 500, "queen": 900 }
          |}""".stripMargin
      assertTrue(
        sample.fromJson[WeightSnapshot] ==
          Right(
            WeightSnapshot(
              version = 1,
              weights = Map(
                "pawn" -> 100,
                "knight" -> 320,
                "bishop" -> 330,
                "rook" -> 500,
                "queen" -> 900
              )
            )
          )
      )
    },
    test("rejects JSON missing the version field") {
      val sample = """{"weights": {"pawn": 100}}"""
      assertTrue(sample.fromJson[WeightSnapshot].isLeft)
    },
    test("rejects JSON missing the weights field") {
      val sample = """{"version": 1}"""
      assertTrue(sample.fromJson[WeightSnapshot].isLeft)
    }
  )

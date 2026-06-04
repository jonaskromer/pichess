package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

object FeatureExtractorSpec extends ZIOSpecDefault:

  private def featuresOf(fen: String): ZIO[Any, chess.model.GameError, Map[String, Int]] =
    FenParserRegex.parse(fen).map(FeatureExtractor.material.features)

  def spec = suite("FeatureExtractor.material")(
    test("returns all zeros on the symmetric starting position") {
      for f <- featuresOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      yield assertTrue(
        f("pawn")   == 0,
        f("knight") == 0,
        f("bishop") == 0,
        f("rook")   == 0,
        f("queen")  == 0,
      )
    },
    test("returns +1 queen when white has an extra queen") {
      for f <- featuresOf("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      yield assertTrue(f("queen") == 1, f("pawn") == 0)
    },
    test("returns negative differences when black is ahead in material") {
      // White lost the h1 rook; black still has both.
      for f <- featuresOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN1 w Qkq - 0 1")
      yield assertTrue(f("rook") == -1)
    },
    test("emits exactly the five tracked feature names (no extras)") {
      for f <- featuresOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
      yield assertTrue(f.keySet == FeatureExtractor.materialNames.toSet)
    },
    test("materialNames covers every feature material emits") {
      for f <- featuresOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      yield assertTrue(FeatureExtractor.materialNames.forall(f.contains))
    },
  )

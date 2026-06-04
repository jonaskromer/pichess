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
  ).+(suite("FeatureExtractor.full")(
    test("starting position has zero net PST contribution — perfect symmetry") {
      for state <- chess.codec.FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
      yield
        val f = FeatureExtractor.full.features(state)
        // Every PST key should net to zero — same number of white
        // pieces on rank N as black pieces on the mirrored rank.
        val pstKeys = FeatureExtractor.pstFeatureNames.toSet
        assertTrue(
          pstKeys.forall(k => f.getOrElse(k, 0) == 0),
          // Material zero, bishop-pair zero too (both sides have both bishops).
          f("pawn") == 0,
          f.getOrElse("bishop_pair", 0) == 0,
        )
    },
    test("a single white knight on f3 contributes +1 to knight_f3 only") {
      // Position: white knight alone on f3 against a black king on h8
      // (need at least both kings for legal-FEN). Other pieces absent.
      for state <- chess.codec.FenParserRegex.parse(
                     "7k/8/8/8/8/5N2/8/4K3 w - - 0 1"
                   )
      yield
        val f = FeatureExtractor.full.features(state)
        assertTrue(
          f("knight_f3") == 1,
          // No other knight squares populated.
          f.getOrElse("knight_b1", 0) == 0,
          f.getOrElse("knight_g1", 0) == 0,
          // Material: +1 white knight.
          f("knight") == 1,
        )
    },
    test("a black pawn on a7 mirrors to pawn_a2 with value -1") {
      // Black pawn on a7, no other pawns. Mirrors to a2 (rank flip).
      for state <- chess.codec.FenParserRegex.parse(
                     "4k3/p7/8/8/8/8/8/4K3 b - - 0 1"
                   )
      yield
        val f = FeatureExtractor.full.features(state)
        assertTrue(
          f("pawn_a2") == -1,
          f.getOrElse("pawn_a7", 0) == 0,
          f("pawn") == -1,
        )
    },
    test("bishop_pair is +1 when only white has the pair") {
      // White: K + 2 bishops (c1 and f1). Black: K only.
      for state <- chess.codec.FenParserRegex.parse(
                     "4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1"
                   )
      yield assertTrue(FeatureExtractor.full.features(state)("bishop_pair") == 1)
    },
    test("bishop_pair is -1 when only black has the pair") {
      for state <- chess.codec.FenParserRegex.parse(
                     "2b1kb2/8/8/8/8/8/8/4K3 b - - 0 1"
                   )
      yield assertTrue(FeatureExtractor.full.features(state)("bishop_pair") == -1)
    },
    test("bishop_pair is 0 when both sides have ≥ 2 bishops or neither does") {
      for
        starting <- chess.codec.FenParserRegex.parse(
                      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                    )
        kingOnly <- chess.codec.FenParserRegex.parse(
                      "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
                    )
      yield assertTrue(
        FeatureExtractor.full.features(starting)("bishop_pair") == 0,
        FeatureExtractor.full.features(kingOnly).getOrElse("bishop_pair", 0) == 0,
      )
    },
    test("pstFeatureNames enumerates exactly 5 × 64 = 320 distinct keys") {
      val names = FeatureExtractor.pstFeatureNames.toList
      assertTrue(
        names.size  == 320,
        names.distinct.size == names.size,
        names.contains("pawn_e4"),
        names.contains("queen_h8"),
      )
    },
  ))

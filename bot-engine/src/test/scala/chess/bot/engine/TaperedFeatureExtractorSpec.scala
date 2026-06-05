package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

object TaperedFeatureExtractorSpec extends ZIOSpecDefault:

  private def taperedOf(fen: String) =
    FenParserRegex.parse(fen).map(TaperedFeatureExtractor.full.features)

  def spec = suite("TaperedFeatureExtractor.full")(
    test("emits both `_mg` and `_eg` variants of every underlying key") {
      // At the starting position the extractor's raw output has keys
      // like "pawn", "knight_b1", "bishop_pair", etc. The tapered
      // wrapper must double them: every key gets `_mg` and `_eg`
      // siblings.
      for
        raw     <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   ).map(FeatureExtractor.full.features)
        tapered <- taperedOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      yield assertTrue(
        // Every raw key has both suffixed variants in the tapered output.
        raw.keySet.forall(k =>
          tapered.contains(s"${k}_mg") && tapered.contains(s"${k}_eg")
        ),
        // No un-suffixed keys leak through.
        tapered.keySet.forall(k => k.endsWith("_mg") || k.endsWith("_eg")),
      )
    },
    test("at full opening (phase = 1.0): all weight is on `_mg`, `_eg` is zero") {
      for tapered <- taperedOf(
                       "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                     )
      yield
        val allEgZero = tapered.iterator.forall {
          case (k, v) if k.endsWith("_eg") => v == 0.0
          case _                            => true
        }
        assertTrue(allEgZero)
    },
    test("at full endgame (phase = 0.0): all weight is on `_eg`, `_mg` is zero") {
      // Bare kings + one white pawn so we have non-zero raw features.
      for tapered <- taperedOf("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
      yield
        val allMgZero = tapered.iterator.forall {
          case (k, v) if k.endsWith("_mg") => v == 0.0
          case _                            => true
        }
        assertTrue(allMgZero)
    },
    test("mid-phase position splits a feature across `_mg` and `_eg`") {
      // Phase ≈ 0.667 — both branches get non-trivial values for any
      // feature with a non-zero raw count.
      for tapered <- taperedOf(
                       "rnbk1bnr/pppppppp/8/8/8/8/PPPPPPPP/RNBK1BNR w - - 0 1"
                     )
      yield
        // No queens → "queen" raw is 0 → no constraint on queen_*.
        // But the bishop is non-zero (white minor piece counts);
        // verify "bishop_mg" + "bishop_eg" sums close to the raw
        // bishop count (which is also 0 for this symmetric position).
        // Pick a non-symmetric feature instead: tempo (raw = 1 for
        // white to move). mg + eg should sum to 1.0 across them.
        val sum = tapered.getOrElse("tempo_mg", 0.0) + tapered.getOrElse("tempo_eg", 0.0)
        assertTrue(math.abs(sum - 1.0) < 1e-9)
    },
    test("over generic FeatureExtractor builds a tapered wrapper") {
      val material = TaperedFeatureExtractor.over(FeatureExtractor.material)
      for state <- FenParserRegex.parse(
                     "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
      yield
        val f = material.features(state)
        // Black missing queen → material "queen" = +1 → splits across
        // queen_mg / queen_eg, but only those two keys (no PSTs).
        assertTrue(
          f.contains("queen_mg"),
          f.contains("queen_eg"),
          f.keySet.forall(k => k.endsWith("_mg") || k.endsWith("_eg")),
          // No PST keys in the material-only tapered extractor.
          !f.keySet.exists(_.contains("a2")),
        )
    },
    test("allFeatureNames covers every full extractor key with both suffixes") {
      val names = TaperedFeatureExtractor.allFeatureNames.toList
      assertTrue(
        // Original full extractor has 345 keys → tapered has 690.
        names.size == 690,
        names.distinct.size == names.size,
        names.contains("pawn_mg"),
        names.contains("pawn_eg"),
        names.contains("knight_outpost_mg"),
        names.contains("tempo_eg"),
      )
    },
  )

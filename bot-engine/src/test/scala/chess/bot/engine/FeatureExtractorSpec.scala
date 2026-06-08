package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex
import chess.model.piece.Color

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
    test("allFeatureNames covers material + PST + scalar features uniquely") {
      val names = FeatureExtractor.allFeatureNames.toList
      assertTrue(
        // 5 material + 320 PST + 23 scalar = 348 total
        names.size == 348,
        names.distinct.size == names.size,
        // Spot checks across categories
        names.contains("pawn"),
        names.contains("knight_e4"),
        names.contains("bishop_pair"),
        names.contains("knight_mobility"),
        names.contains("passed_rank_5"),
        names.contains("isolated_pawn"),
        names.contains("rook_open_file"),
        names.contains("knight_outpost"),
        names.contains("tempo"),
      )
    },
    suite("mobility")(
      test("starting position has zero net mobility (perfect symmetry)") {
        for state <- chess.codec.FenParserRegex.parse(
                       "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                     )
        yield
          val f = FeatureExtractor.full.features(state)
          assertTrue(
            f.getOrElse("knight_mobility", 0) == 0,
            f.getOrElse("bishop_mobility", 0) == 0,
            f.getOrElse("rook_mobility",   0) == 0,
            f.getOrElse("queen_mobility",  0) == 0,
          )
      },
      test("knight in the centre has more moves than a corner knight") {
        // Two boards: knight on d4 vs knight on a1. Same kings.
        for
          central <- chess.codec.FenParserRegex.parse("4k3/8/8/8/3N4/8/8/4K3 w - - 0 1")
          corner  <- chess.codec.FenParserRegex.parse("4k3/8/8/8/8/8/8/N3K3 w - - 0 1")
        yield assertTrue(
          FeatureExtractor.full.features(central)("knight_mobility") >
            FeatureExtractor.full.features(corner)("knight_mobility"),
        )
      },
    ),
    suite("passed pawns")(
      test("a white pawn on e5 with no enemy pawns on d/e/f files is passed") {
        // White: K e1 + pawn e5. Black: K e8 (no pawns).
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/4P3/8/8/8/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("passed_rank_5") == 1
        )
      },
      test("a white pawn blocked by an enemy pawn on the same file is NOT passed") {
        // White: K e1 + pawn e5. Black: K e8 + pawn e7 (blocking).
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/4p3/8/4P3/8/8/8/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state).getOrElse("passed_rank_5", 0) == 0
        )
      },
      test("a black passed pawn mirrors to white-rank-equivalent feature") {
        // Black: K e8 + pawn e4 (rank 4 → mirror rank 5).
        // White: K e1, no pawns.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/4p3/8/8/4K3 b - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("passed_rank_5") == -1
        )
      },
    ),
    suite("pawn weaknesses")(
      test("isolated pawn counts when no friendly pawn on adjacent files") {
        // White pawns: a4 and h4 (both isolated — empty b- and g-files).
        // Black: K only.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/P6P/8/8/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("isolated_pawn") == 2
        )
      },
      test("doubled pawn counts the extra pawns per file") {
        // Three white pawns on a-file: a2, a3, a4. doubled = 3 - 1 = 2.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/P7/P7/P7/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("doubled_pawn") == 2
        )
      },
      test("connected pawns count when adjacent-file pawn is within ±1 rank") {
        // White pawns on a2 and b3 — connected (adjacent files, 1 rank apart).
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/8/1P6/P7/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("connected_pawn") == 2
        )
      },
    ),
    suite("king safety")(
      test("pawn shield counts own pawns near own king") {
        // White: K g1 with pawns on f2, g2, h2 (perfect shield).
        // Black: K g8 alone.
        for state <- chess.codec.FenParserRegex.parse(
                       "6k1/8/8/8/8/8/5PPP/6K1 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("pawn_shield") == 3
        )
      },
      test("king attackers counts enemy non-pawn pieces hitting king zone") {
        // Black queen on g4 attacks white king zone (g1 area).
        // White king g1, no other pieces; black king e8.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/6q1/8/8/6K1 w - - 0 1"
                     )
        yield
          val f = FeatureExtractor.full.features(state)
          // black queen attacking white king zone → black has 1 attacker on white,
          // white has 0 attackers on black → king_attackers = 0 - 1 = -1.
          assertTrue(f("king_attackers") == -1)
      },
    ),
    suite("rook activity")(
      test("rook on open file (no pawns of either side on the file)") {
        // White rook on a1, no pawns anywhere on a-file.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/8/8/8/R3K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("rook_open_file") == 1
        )
      },
      test("rook on semi-open file (own pawn absent, enemy pawn present)") {
        // White rook a1, black pawn a7 → a-file semi-open for white.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/p7/8/8/8/8/8/R3K3 w - - 0 1"
                     )
        yield
          val f = FeatureExtractor.full.features(state)
          assertTrue(
            f("rook_semi_open_file") == 1,
            // Not open (black pawn on the file).
            f.getOrElse("rook_open_file", 0) == 0,
          )
      },
      test("rook with own pawn on the file is neither open nor semi-open") {
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/8/8/P7/R3K3 w - - 0 1"
                     )
        yield
          val f = FeatureExtractor.full.features(state)
          assertTrue(
            f.getOrElse("rook_open_file",      0) == 0,
            f.getOrElse("rook_semi_open_file", 0) == 0,
          )
      },
    ),
    suite("knight outpost")(
      test("knight defended by pawn with no enemy pawn attack is an outpost") {
        // White knight on e5 (rank 5 — enemy half ✓), defended by white
        // pawn on d4. No black pawns to attack e5.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/4N3/3P4/8/8/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state)("knight_outpost") == 1
        )
      },
      test("knight in own half is NOT counted as outpost") {
        // White knight on c3 (rank 3 — own half), defended by pawn b2.
        for state <- chess.codec.FenParserRegex.parse(
                       "4k3/8/8/8/8/2N5/1P6/4K3 w - - 0 1"
                     )
        yield assertTrue(
          FeatureExtractor.full.features(state).getOrElse("knight_outpost", 0) == 0
        )
      },
    ),
    suite("tempo")(
      test("+1 when white to move") {
        for state <- chess.codec.FenParserRegex.parse(
                       "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                     )
        yield assertTrue(FeatureExtractor.full.features(state)("tempo") == 1)
      },
      test("-1 when black to move") {
        for state <- chess.codec.FenParserRegex.parse(
                       "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
                     )
        yield assertTrue(FeatureExtractor.full.features(state)("tempo") == -1)
      },
    ),
    suite("kingless guard")(
      test("missing king bitboard returns no king-safety features (defensive)") {
        // BoardState.Empty has both king bitboards at 0 — used in
        // some tests. The extractor must not throw or index out of
        // bounds; it just omits king-safety contributions.
        val empty = chess.model.board.GameState(
          board       = chess.model.board.BoardState.Empty,
          activeColor = Color.White,
        )
        val f = FeatureExtractor.full.features(empty)
        assertTrue(
          f.getOrElse("pawn_shield",    0) == 0,
          f.getOrElse("king_attackers", 0) == 0,
          // Other features still emit normally (all 0 on empty board).
          f("pawn") == 0,
        )
      },
    ),
  ))

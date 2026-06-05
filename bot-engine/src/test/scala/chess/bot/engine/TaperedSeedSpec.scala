package chess.bot.engine

import zio.test.*

object TaperedSeedSpec extends ZIOSpecDefault:

  def spec = suite("TaperedFeatureExtractor seed helpers")(
    suite("defaultSeedWeights")(
      test("has both _mg and _eg entries for every feature name") {
        val seed = TaperedFeatureExtractor.defaultSeedWeights
        val names = TaperedFeatureExtractor.allFeatureNames.toList
        assertTrue(
          seed.keySet == names.toSet,
          // Every key resolves to an Int (no Nones).
          names.forall(seed.contains),
        )
      },
      test("material has canonical centipawn seeds for both phases") {
        val seed = TaperedFeatureExtractor.defaultSeedWeights
        assertTrue(
          seed("pawn_mg")   == 100, seed("pawn_eg")   == 100,
          seed("knight_mg") == 320, seed("knight_eg") == 320,
          seed("bishop_mg") == 330, seed("bishop_eg") == 330,
          seed("rook_mg")   == 500, seed("rook_eg")   == 500,
          seed("queen_mg")  == 900, seed("queen_eg")  == 900,
        )
      },
      test("non-material features seed to 0") {
        val seed = TaperedFeatureExtractor.defaultSeedWeights
        assertTrue(
          seed("pawn_a2_mg") == 0,
          seed("knight_outpost_eg") == 0,
          seed("tempo_mg") == 0,
        )
      },
    ),
    suite("promoteToTapered")(
      test("duplicates each legacy weight into both `_mg` and `_eg` slots") {
        val legacy = Map("pawn" -> 100, "knight" -> 320)
        val promoted = TaperedFeatureExtractor.promoteToTapered(legacy)
        assertTrue(
          promoted("pawn_mg")   == 100,
          promoted("pawn_eg")   == 100,
          promoted("knight_mg") == 320,
          promoted("knight_eg") == 320,
        )
      },
      test("preserves already-tapered keys unchanged") {
        val mixed = Map("pawn" -> 100, "pawn_mg" -> 120, "pawn_eg" -> 80)
        val promoted = TaperedFeatureExtractor.promoteToTapered(mixed)
        assertTrue(
          // mg/eg slots already set — must NOT be overwritten by the legacy 100.
          promoted("pawn_mg") == 120,
          promoted("pawn_eg") == 80,
        )
      },
      test("a fully-tapered snapshot round-trips unchanged") {
        val tapered = Map("pawn_mg" -> 100, "pawn_eg" -> 110, "knight_mg" -> 320, "knight_eg" -> 340)
        assertTrue(TaperedFeatureExtractor.promoteToTapered(tapered) == tapered)
      },
    ),
  )

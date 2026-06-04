package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

object EngineBundleSpec extends ZIOSpecDefault:

  private val startFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def spec = suite("EngineBundle")(
    suite("fromResources")(
      test("assembles weights, book, and a working search from committed resources") {
        for
          bundle <- EngineBundle.fromResources()
          state  <- FenParserRegex.parse(startFen)
          move   <- bundle.search.bestMove(state, depth = 2)
        yield assertTrue(
          bundle.weights.version == 1,
          move.isDefined,                        // search returned something
          // Loaded book recognises the start position via main lines
          (bundle.openingBook eq OpeningBook.Empty) == false,
        )
      },
      test("fails when an unknown weights version is requested") {
        for result <- EngineBundle.fromResources(weightsVersion = 999).exit
        yield assertTrue(result.isFailure)
      },
    ),
    suite("fromResourcesOrFallback")(
      test("returns the loaded bundle + no error on success") {
        for
          (bundle, err) <- EngineBundle.fromResourcesOrFallback()
        yield assertTrue(
          err.isEmpty,
          bundle.weights.version == 1,
        )
      },
      test("returns a working fallback bundle + the error on failure") {
        for
          (bundle, err) <- EngineBundle.fromResourcesOrFallback(weightsVersion = 999)
          state         <- FenParserRegex.parse(startFen)
          move          <- bundle.search.bestMove(state, depth = 2)
        yield assertTrue(
          err.isDefined,                          // we got the load error
          bundle.weights.version == 0,            // fallback snapshot
          move.isDefined,                         // search still works
        )
      },
    ),
  )

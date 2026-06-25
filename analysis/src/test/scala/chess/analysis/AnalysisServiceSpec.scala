package chess.analysis

import zio.*
import zio.test.*

import chess.bot.engine.{Evaluator, Search}
import chess.opening.{EcoBook, EcoEntry}

object AnalysisServiceSpec extends ZIOSpecDefault:

  private val eco = EcoBook.fromEntries(
    Vector(EcoEntry("B20", "Sicilian Defense", List("e4", "c5")))
  )
  private val analyzer = GameAnalyzer(Search.alphaBeta(Evaluator.materialOnly), eco)
  private val service  = AnalysisService(analyzer)

  def spec = suite("AnalysisService")(
    test("parses a PGN and produces the analysis DTO") {
      for dto <- service.analyze("1. e4 c5 2. Nf3 d6 *", depth = 2)
      yield assertTrue(
        dto.opening.name == "Sicilian Defense",
        dto.moves.length == 4,
        dto.moves.head.moveClass == "Book"
      )
    },
    test("cache returns the same result and computes once per (pgn, depth)") {
      val pgn = "1. e4 c5 *"
      for
        cached <- CachedAnalysisService.make(service)
        first  <- cached.analyze(pgn, 2) // miss
        second <- cached.analyze(pgn, 2) // hit
      yield assertTrue(first == second, first.moves.length == 2)
    }
  )

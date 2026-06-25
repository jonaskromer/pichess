package chess.analysis

import zio.*
import zio.test.*

import chess.bot.engine.EngineBundle
import chess.codec.PgnParser
import chess.opening.EcoBook

/** Integration + regression test for full-game analysis with the REAL
  * production engine (NNUE+HCE hybrid, quiescence on) — the existing
  * `GameAnalyzerSpec` only exercises a material-only evaluator at depth 1-2, so
  * it never caught that the production depth was set too high to finish.
  *
  * Uses the user-reported tactical game (lots of captures/checks → a worst case
  * for quiescence) at the production default depth, and asserts it completes
  * comfortably inside a wall-clock bound. If the default depth is bumped back up
  * (depth 10 took minutes on this game), the timeout here trips.
  */
object FullGameAnalysisSpec extends ZIOSpecDefault:

  private val pgn =
    "1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 " +
      "7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 " +
      "12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ " +
      "Nxb8 17. Rd8# 1-0"

  // Mirrors GrpcServer.DefaultAnalysisDepth — the depth the service actually
  // runs. Kept in sync by intent; the point of the test is that THIS depth
  // finishes a full game quickly.
  private val ProdDepth = 4

  def spec = suite("full-game analysis (real engine)")(
    test("the reported tactical game analyses at the production depth, fast") {
      for
        res <- EngineBundle.fromResourcesOrFallback()
        (bundle, _) = res
        analyzer = GameAnalyzer(bundle.search, EcoBook.fromEntries(Vector.empty))
        game     <- PgnParser.parse(pgn)
        analysis <- analyzer.analyze(game.initialState, game.history, ProdDepth)
      yield assertTrue(
        // Every ply rated, end to end (the last move is the mate).
        game.history.length == 33,
        analysis.moves.length == 33,
        analysis.moves.head.color == "white",
        analysis.moves.last.san == "Rd8#",
        analysis.accuracyWhite >= 0.0 && analysis.accuracyWhite <= 100.0,
        analysis.accuracyBlack >= 0.0 && analysis.accuracyBlack <= 100.0
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(90.seconds)
  )

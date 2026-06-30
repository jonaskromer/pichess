package chess.bot.engine

import zio.*
import zio.test.*

import chess.bot.engine.nnue.NnueEvaluator
import chess.codec.FenParserRegex

object LazySmpBudgetSpec extends ZIOSpecDefault:

  private val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get

  def spec = suite("LazySMP budget")(
    test(
      "ParallelismBudget grabs spare permits non-blocking, caps at available, releases"
    ) {
      val b = new ParallelismBudget(3)
      val a1 = b.acquireHelpers(5) // wants 5, only 3 free
      val a2 = b.acquireHelpers(5) // none left → 0 (never blocks)
      b.release(a1)
      val a3 = b.acquireHelpers(2) // 3 free again → 2
      assertTrue(
        a1 == 3,
        a2 == 0,
        a3 == 2,
        ParallelismBudget.Single.acquireHelpers(4) == 0
      )
    },
    test("a lone main worker keeps the full helper budget (no 1-game regression)") {
      val b = new ParallelismBudget(3)
      b.enter() // one active search
      assertTrue(b.acquireHelpers(3) == 3)
    },
    test("each additional concurrent main worker reserves a core for itself") {
      val b2 = new ParallelismBudget(3)
      b2.enter(); b2.enter() // two concurrent searches → one core reserved
      val two = new ParallelismBudget(3)
      two.enter(); two.enter(); two.enter() // three → two cores reserved
      assertTrue(b2.acquireHelpers(3) == 2, two.acquireHelpers(3) == 1)
    },
    test("more mains than permits floor the helper cap at zero (never negative)") {
      val b = new ParallelismBudget(2)
      b.enter(); b.enter(); b.enter(); b.enter() // 4 mains, 2 permits
      assertTrue(b.acquireHelpers(2) == 0)
    },
    test("leave() releases a main's core reservation") {
      val b = new ParallelismBudget(3)
      b.enter(); b.enter() // cap → 2
      b.leave() // back to one active → full budget
      assertTrue(b.acquireHelpers(3) == 3)
    },
    test(
      "overlapping budgeted-LazySMP searches stay legal, don't crash, don't leak permits"
    ) {
      // One shared search (the harder case: shared TT + killer/history tables),
      // LazySMP on, incremental un-gated — exactly the concurrency the bot API
      // hits when several games search at once.
      val budget = new ParallelismBudget(3)
      val search = new AlphaBetaSearch(
        eval = nnue,
        tt = TranspositionTable.inMemory(1 << 16),
        book = OpeningBook.Empty,
        lazySmpEnabled = true,
        budget = budget
      )
      val fens = List(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
      )
      for
        states <- ZIO.foreach(fens)(f => FenParserRegex.parse(f).orDie)
        // 12 overlapping searches (3× the positions) sharing one search + budget
        moves <- ZIO.foreachPar(states ++ states ++ states)(s =>
          search.bestMoveWithBudget(s, budgetMillis = 60)
        )
      yield assertTrue(
        moves.forall(_.isDefined), // every search returned a legal move
        budget.available == 3 // every acquired helper permit was released
      )
    }
  )

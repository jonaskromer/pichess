package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex
import chess.model.board.{Move, Position}
import chess.model.rules.Zobrist

/** Verifies the book → search hand-off. When the book has a move,
  * Search must return it as-is (no α-β work) — this is the
  * fast-path that makes the book worth wiring at all.
  */
object SearchWithBookSpec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  // A clearly-suboptimal move that no real material search would pick
  // at depth ≥ 1: 1. h4 (the "Despres opening" — pure weak hack).
  // The book overriding the search with this move proves the wire-up.
  private val bookMove = Move(Position('h', 2), Position('h', 4), None)

  def spec = suite("Search with OpeningBook")(
    test("returns the book move at a known position") {
      for
        state <- FenParserRegex.parse(startFen)
        book   = OpeningBook.inMemory(Map(Zobrist.hash(state) -> bookMove))
        search = Search.alphaBeta(Evaluator.materialOnly, book)
        out   <- search.bestMove(state, depth = 3)
      yield assertTrue(out.contains(bookMove))
    },
    test("falls through to α-β search at an unknown position") {
      // Book contains only the post-1.e4 position; the root position
      // isn't in the book, so search runs and returns *some* legal
      // move. We can't usefully assert which one — with material-only
      // eval at depth 2 from start, every opening tie at score 0, so
      // the chosen move depends on the move generator's enumeration
      // order and the sort's tiebreak, neither of which is a stable
      // contract.
      for
        state <- FenParserRegex.parse(startFen)
        otherFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        otherState <- FenParserRegex.parse(otherFen)
        book = OpeningBook.inMemory(Map(Zobrist.hash(otherState) -> bookMove))
        search = Search.alphaBeta(Evaluator.materialOnly, book)
        out   <- search.bestMove(state, depth = 2)
      yield assertTrue(out.isDefined)
    },
  )

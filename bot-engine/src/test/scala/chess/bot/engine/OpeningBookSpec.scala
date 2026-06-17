package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex
import chess.model.board.{Move, Position}
import chess.model.piece.Color
import chess.model.rules.Zobrist

object OpeningBookSpec extends ZIOSpecDefault:

  private val startFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val e4Move = Move(Position('e', 2), Position('e', 4), None)

  def spec = suite("OpeningBook")(
    suite("ply")(
      test(
        "returns 0 on the standard starting position (white to move, fullmove 1)"
      ) {
        for state <- FenParserRegex.parse(startFen)
        yield assertTrue(OpeningBook.ply(state) == 0)
      },
      test("returns 1 after white's first move (black to move, fullmove 1)") {
        for state <- FenParserRegex.parse(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
          )
        yield assertTrue(OpeningBook.ply(state) == 1)
      },
      test("returns 2 after 1.e4 e5 (white to move, fullmove 2)") {
        for state <- FenParserRegex.parse(
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
          )
        yield assertTrue(OpeningBook.ply(state) == 2)
      }
    ),
    suite("Empty book")(
      test("returns None at every position") {
        for
          state <- FenParserRegex.parse(startFen)
          out <- OpeningBook.Empty.lookup(state)
        yield assertTrue(out.isEmpty)
      }
    ),
    suite("inMemory")(
      test("returns the configured move when the position is in the book") {
        for
          state <- FenParserRegex.parse(startFen)
          book = OpeningBook.inMemory(
            Map(Zobrist.hash(state) -> Vector(e4Move))
          )
          out <- book.lookup(state)
        yield assertTrue(out.contains(e4Move))
      },
      test(
        "with several book moves for one position, returns one of them (variety)"
      ) {
        val d4Move = Move(Position('d', 2), Position('d', 4), None)
        for
          state <- FenParserRegex.parse(startFen)
          book = OpeningBook.inMemory(
            Map(Zobrist.hash(state) -> Vector(e4Move, d4Move, e4Move))
          )
          picks <- ZIO.foreach(1 to 20)(_ => book.lookup(state))
        yield assertTrue(
          picks.forall(_.exists(m => m == e4Move || m == d4Move))
        )
      },
      test("returns None when the position is not in the book") {
        for
          state <- FenParserRegex.parse(startFen)
          book = OpeningBook.inMemory(Map.empty)
          out <- book.lookup(state)
        yield assertTrue(out.isEmpty)
      },
      test("returns None past the configured maxPly even on a known position") {
        for
          // Construct a state with high fullmove number so its ply
          // exceeds the maxPly threshold below.
          state <- FenParserRegex.parse(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 30"
          )
          book = OpeningBook.inMemory(
            Map(Zobrist.hash(state) -> Vector(e4Move)),
            maxPly = 24
          )
          out <- book.lookup(state)
        yield assertTrue(out.isEmpty)
      }
    )
  )

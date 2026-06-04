package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex
import chess.model.board.Position
import chess.model.rules.Zobrist

object OpeningBookLoaderSpec extends ZIOSpecDefault:

  private val startFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def spec = suite("OpeningBookLoader")(
    suite("loadDefault")(
      test("loads the committed main-lines PGN and recognises the starting position") {
        for
          book  <- OpeningBookLoader.loadDefault()
          state <- FenParserRegex.parse(startFen)
          move  <- book.lookup(state)
        yield assertTrue(
          // The committed file's first game starts with 1.e4 or 1.d4
          // depending on order; either way the starting position
          // should be in book.
          move.isDefined,
        )
      },
      test("recognises a position several plies into a known main line") {
        // After 1.e4 e5 — a position EVERY committed line through Ruy
        // Lopez / Italian / Petrov visits. Book must reply with a
        // sensible second move (Nf3 in the canonical lines).
        val afterE4E5 =
          "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
        for
          book  <- OpeningBookLoader.loadDefault()
          state <- FenParserRegex.parse(afterE4E5)
          move  <- book.lookup(state)
        yield assertTrue(move.isDefined)
      },
      test("returns None for a position not in any main line") {
        // The "Bongcloud" — 1.e4 e5 2.Ke2 — is in nobody's main line.
        // Book should refuse.
        val bongcloud =
          "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPPKPPP/RNBQ1BNR b kq - 1 2"
        for
          book  <- OpeningBookLoader.loadDefault()
          state <- FenParserRegex.parse(bongcloud)
          move  <- book.lookup(state)
        yield assertTrue(move.isEmpty)
      },
    ),
    suite("loadResource")(
      test("fails with MissingResource for a non-existent path") {
        for result <- OpeningBookLoader.loadResource("openings/nope.pgn").exit
        yield assertTrue(
          result.causeOption.exists(_.failureOption.exists {
            case _: OpeningBookLoader.MissingResource => true
            case _                                    => false
          })
        )
      },
    ),
    suite("historyToEntries")(
      test("emits one entry per (pre-state, move) pair") {
        // A tiny PGN parsed in-place.
        val miniPgn =
          """[Event "Mini"]
            |[Result "*"]
            |
            |1. e4 e5 2. Nf3 *
            |""".stripMargin
        for game <- chess.codec.PgnParser.parse(miniPgn)
        yield assertTrue(
          OpeningBookLoader.historyToEntries(game).size == 3
        )
      },
    ),
    suite("splitGames")(
      test("splits at the [Event marker") {
        val joined =
          """[Event "A"]
            |
            |1. e4 *
            |
            |[Event "B"]
            |
            |1. d4 *""".stripMargin
        assertTrue(
          OpeningBookLoader.splitGames(joined).size == 2,
          OpeningBookLoader.splitGames(joined).head.contains("\"A\""),
        )
      },
      test("returns Nil for empty input") {
        assertTrue(OpeningBookLoader.splitGames("").isEmpty)
      },
    ),
  )

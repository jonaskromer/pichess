package chess.bot.data

import zio.*
import zio.test.*

import chess.bot.engine.OpeningBook
import chess.codec.FenParserRegex
import chess.model.board.{Move, Position}
import chess.model.rules.Zobrist

object DuckDbOpeningBookSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")
  private val startFen  =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def spec = suite("DuckDbOpeningBook")(
    test("returns None when the repo has no row for the position") {
      ZIO.scoped {
        for
          conn  <- Db.open(memoryCfg)
          repo   = BookRepo.duckdb(conn)
          book   = DuckDbOpeningBook.fromRepo(repo)
          state <- FenParserRegex.parse(startFen)
          out   <- book.lookup(state)
        yield assertTrue(out.isEmpty)
      }
    },
    test("returns the best-move from the repo as a Move") {
      ZIO.scoped {
        for
          conn  <- Db.open(memoryCfg)
          repo   = BookRepo.duckdb(conn)
          state <- FenParserRegex.parse(startFen)
          _     <- repo.upsert(Chunk(BookRow(
                     zobrist = Zobrist.hash(state),
                     moveUci = "e2e4",
                     wins    = 10, draws = 0, losses = 0,
                     sumElo  = 20000,
                   )))
          book   = DuckDbOpeningBook.fromRepo(repo)
          out   <- book.lookup(state)
        yield assertTrue(
          out.contains(Move(Position('e', 2), Position('e', 4), None))
        )
      }
    },
    test("returns None past the configured maxPly") {
      ZIO.scoped {
        for
          conn  <- Db.open(memoryCfg)
          repo   = BookRepo.duckdb(conn)
          // Fullmove 30 → ply ≥ 58, well past any reasonable maxPly.
          state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 30"
                   )
          _     <- repo.upsert(Chunk(BookRow(
                     zobrist = Zobrist.hash(state),
                     moveUci = "e2e4",
                     wins = 1, draws = 0, losses = 0, sumElo = 2000,
                   )))
          book   = DuckDbOpeningBook.fromRepo(repo, maxPly = 24)
          out   <- book.lookup(state)
        yield assertTrue(out.isEmpty)
      }
    },
    test("returns None when the stored UCI is malformed") {
      // Defensive guard: a corrupted DB row shouldn't crash the bot —
      // the adapter parses safely and treats a malformed UCI as
      // "no book move".
      ZIO.scoped {
        for
          conn  <- Db.open(memoryCfg)
          repo   = BookRepo.duckdb(conn)
          state <- FenParserRegex.parse(startFen)
          _     <- repo.upsert(Chunk(BookRow(
                     zobrist = Zobrist.hash(state),
                     moveUci = "garbage",
                     wins = 1, draws = 0, losses = 0, sumElo = 2000,
                   )))
          book   = DuckDbOpeningBook.fromRepo(repo)
          out   <- book.lookup(state)
        yield assertTrue(out.isEmpty)
      }
    },
    test("handles promotion in the stored UCI") {
      ZIO.scoped {
        for
          conn  <- Db.open(memoryCfg)
          repo   = BookRepo.duckdb(conn)
          state <- FenParserRegex.parse(startFen)
          _     <- repo.upsert(Chunk(BookRow(
                     zobrist = Zobrist.hash(state),
                     moveUci = "e7e8q",
                     wins = 1, draws = 0, losses = 0, sumElo = 2000,
                   )))
          book   = DuckDbOpeningBook.fromRepo(repo)
          out   <- book.lookup(state)
        yield assertTrue(
          out.exists(m =>
            m.from == Position('e', 7) &&
            m.to   == Position('e', 8) &&
            m.promotion.contains(chess.model.piece.PieceType.Queen)
          )
        )
      }
    },
  )

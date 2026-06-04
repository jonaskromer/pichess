package chess.bot.train

import zio.*
import zio.test.*

import chess.bot.data.{BookRepo, Db, TrainingRepo}
import chess.codec.PgnParser
import chess.model.board.Position
import chess.model.piece.Color
import chess.model.rules.Zobrist

/** Smoke + correctness tests for the PGN-ingest pipeline. Uses
  * `:memory:` DuckDB so the suite is hermetic and runs in millis. */
object PgnIngestSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  /** Standard Ruy Lopez fragment with explicit headers — short enough
    * to reason about by hand, long enough to exercise every code path
    * (white + black moves, mate-free middle game, captures, …).
    *
    * Result is "1-0" so white wins; both sides have Elo set so the
    * sum_elo accumulator gets visible values. */
  private val sampleGame: String =
    """[Event "Sample"]
      |[Site "test"]
      |[Date "2024.01.01"]
      |[Round "1"]
      |[White "Alice"]
      |[Black "Bob"]
      |[Result "1-0"]
      |[WhiteElo "2200"]
      |[BlackElo "2100"]
      |[ECO "C60"]
      |
      |1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 1-0
      |""".stripMargin

  /** Same fragment with Result reversed and lower Elos — used for the
    * counter-accumulation test (two ingests of related games into
    * the same starting move). */
  private val sampleBlackWin: String =
    """[Event "Sample 2"]
      |[Site "test"]
      |[Date "2024.01.02"]
      |[Round "2"]
      |[White "Alice"]
      |[Black "Bob"]
      |[Result "0-1"]
      |[WhiteElo "1500"]
      |[BlackElo "1500"]
      |[ECO "C60"]
      |
      |1. e4 e5 2. Nf3 Nc6 0-1
      |""".stripMargin

  def spec = suite("PgnIngest")(
    suite("ingestOne")(
      test("returns Stats.Zero for a malformed PGN") {
        ZIO.scoped {
          for
            conn  <- Db.open(memoryCfg)
            book   = BookRepo.duckdb(conn)
            train  = TrainingRepo.duckdb(conn)
            stats <- PgnIngest.ingestOne("not a pgn", book, train)
          yield assertTrue(stats == PgnIngest.Stats.Zero)
        }
      },
      test("reports a non-zero row count for a well-formed game") {
        // The sample is 7 plies long → 7 BookRows + 7 TrainingRows.
        ZIO.scoped {
          for
            conn  <- Db.open(memoryCfg)
            book   = BookRepo.duckdb(conn)
            train  = TrainingRepo.duckdb(conn)
            stats <- PgnIngest.ingestOne(sampleGame, book, train)
            count <- train.count
          yield assertTrue(
            stats.games == 1L,
            stats.bookRows == 7L,
            stats.trainingRows == 7L,
            count == 7L,
          )
        }
      },
      test("ingests the opening so the starting move is queryable") {
        ZIO.scoped {
          for
            conn  <- Db.open(memoryCfg)
            book   = BookRepo.duckdb(conn)
            train  = TrainingRepo.duckdb(conn)
            _     <- PgnIngest.ingestOne(sampleGame, book, train)
            // Zobrist of the starting position
            startState <- PgnParser.parse(sampleGame).map(_.initialState)
            rows  <- book.lookup(Zobrist.hash(startState))
          yield assertTrue(
            rows.size == 1,
            rows.head.moveUci == "e2e4",
            rows.head.wins == 1L,
            rows.head.draws == 0L,
            rows.head.losses == 0L,
            rows.head.sumElo == 2200L,
          )
        }
      },
    ),
    suite("ingestMany")(
      test("aggregates counters across games of the same opening") {
        // Both games open 1.e4; the first is "1-0" (white wins, white
        // is the side to move) → wins += 1. The second is "0-1"
        // (black wins, white loses) → losses += 1. Aggregated sum_elo
        // is the sum of WhiteElo across both games (2200 + 1500 = 3700).
        val twoGames = sampleGame + "\n" + sampleBlackWin
        ZIO.scoped {
          for
            conn  <- Db.open(memoryCfg)
            book   = BookRepo.duckdb(conn)
            train  = TrainingRepo.duckdb(conn)
            stats <- PgnIngest.ingestMany(twoGames, book, train)
            startState <- PgnParser.parse(sampleGame).map(_.initialState)
            rows  <- book.lookup(Zobrist.hash(startState))
          yield assertTrue(
            stats.games == 2L,
            rows.size == 1,
            rows.head.moveUci == "e2e4",
            rows.head.wins == 1L,
            rows.head.losses == 1L,
            rows.head.sumElo == 3700L,
          )
        }
      },
      test("returns Stats.Zero for an empty corpus") {
        ZIO.scoped {
          for
            conn  <- Db.open(memoryCfg)
            book   = BookRepo.duckdb(conn)
            train  = TrainingRepo.duckdb(conn)
            stats <- PgnIngest.ingestMany("", book, train)
          yield assertTrue(stats == PgnIngest.Stats.Zero)
        }
      },
    ),
    suite("buildRows (pure)")(
      test("training rows label outcome from side-to-move perspective") {
        for
          game <- PgnParser.parse(sampleGame)
        yield
          val (_, trainingRows) = PgnIngest.buildRows(game)
          // First move (1. e4) is by white in a game white wins →
          // outcome from white's perspective = 1.0.
          val firstOutcome = trainingRows.head.outcome
          // Second move (1...e5) is by black in a game black loses →
          // outcome from black's perspective = 0.0.
          val secondOutcome = trainingRows(1).outcome
          assertTrue(firstOutcome == 1.0f, secondOutcome == 0.0f)
      },
      test("unfinished result (\"*\") emits 0/0/0 counters") {
        val unfinishedPgn =
          """[Event "x"]
            |[Result "*"]
            |
            |1. e4 *
            |""".stripMargin
        for
          game <- PgnParser.parse(unfinishedPgn)
        yield
          val (bookRows, _) = PgnIngest.buildRows(game)
          assertTrue(
            bookRows.head.wins == 0L,
            bookRows.head.draws == 0L,
            bookRows.head.losses == 0L,
          )
      },
      test("draw result emits a draws=1 row") {
        val drawPgn =
          """[Event "draw"]
            |[Result "1/2-1/2"]
            |[WhiteElo "2000"]
            |[BlackElo "2000"]
            |
            |1. e4 e5 1/2-1/2
            |""".stripMargin
        for game <- PgnParser.parse(drawPgn)
        yield
          val (bookRows, _) = PgnIngest.buildRows(game)
          assertTrue(
            bookRows.forall(_.draws == 1L),
            bookRows.forall(r => r.wins == 0L && r.losses == 0L),
          )
      },
      test("captures and check positions are NOT marked quiet") {
        // Scholar's mate position has Qxf7# (capture + check + mate).
        val captureMate =
          """[Event "smothered"]
            |[Result "1-0"]
            |
            |1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0
            |""".stripMargin
        for game <- PgnParser.parse(captureMate)
        yield
          val (_, trainingRows) = PgnIngest.buildRows(game)
          // The 7th ply (4. Qxf7#) is a capture-and-check; its training
          // row must NOT be marked quiet. Earlier quiet plies should be.
          val lastRow = trainingRows.last
          assertTrue(
            !lastRow.quiet,
            // The very first move (1. e4) is quiet — no capture, no check.
            trainingRows.head.quiet,
          )
      },
    ),
    suite("splitGames")(
      test("splits a two-game string at the [Event marker") {
        val joined = sampleGame + sampleBlackWin
        val split = PgnIngest.splitGames(joined)
        assertTrue(
          split.size == 2,
          split.head.contains("[White \"Alice\"]"),
          split(1).contains("[Result \"0-1\"]"),
        )
      },
      test("returns Nil for empty input") {
        assertTrue(PgnIngest.splitGames("").isEmpty)
      },
    ),
  )

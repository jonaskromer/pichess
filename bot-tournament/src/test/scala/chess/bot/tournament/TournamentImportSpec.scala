package chess.bot.tournament

import zio.test.*

import chess.bot.tournament.TournamentApiClient.{
  AnalyticsExport,
  AnalyticsExportGame,
  AnalyticsExportStanding
}

/** Guards the post-tournament archive transform
  * ([[TournamentImport.opponentSubmissions]]): from a finished tournament's
  * analytics-export, build archive submissions for every game we did NOT play
  * (ours are archived live), mapping winner → PGN result and splitting the UCI.
  */
object TournamentImportSpec extends ZIOSpecDefault:

  private val clock = TournamentClock(limit = 300, increment = 2)

  private def game(
      id: String,
      wId: String,
      w: String,
      bId: String,
      b: String,
      winner: Option[String],
      moves: String
  ): AnalyticsExportGame =
    AnalyticsExportGame(id, wId, w, bId, b, winner, moves)

  def spec = suite("TournamentImport.opponentSubmissions")(
    test("skips the games our bot played (white or black), keeps the rest") {
      val exp = AnalyticsExport(
        "t1",
        clock,
        List(
          game("g1", "me", "pichess", "x", "Stockfish", Some("white"), "e2e4"),
          game("g2", "x", "Stockfish", "me", "pichess", Some("black"), "d2d4"),
          game("g3", "x", "Stockfish", "y", "Leela", Some("draw"), "c2c4")
        )
      )
      val subs = TournamentImport.opponentSubmissions(exp, "me")
      // Only g3 (neither side is us) survives.
      assertTrue(subs.map(_.gameId) == List("g3"))
    },
    test("maps winner → PGN result token (incl. the unknown fallback)") {
      val exp = AnalyticsExport(
        "t1",
        clock,
        List(
          game("g1", "a", "A", "b", "B", Some("white"), "e2e4"),
          game("g2", "a", "A", "b", "B", Some("black"), "e2e4"),
          game("g3", "a", "A", "b", "B", Some("draw"), "e2e4"),
          game("g4", "a", "A", "b", "B", None, "e2e4")
        )
      )
      val subs = TournamentImport.opponentSubmissions(exp, "me")
      assertTrue(
        subs.map(_.result) == List("1-0", "0-1", "1/2-1/2", "*")
      )
    },
    test("splits UCI moves and tags source / time control / players") {
      val exp = AnalyticsExport(
        "t7",
        clock,
        List(game("g1", "a", "Alice", "b", "Bob", Some("white"), "e2e4  e7e5 g1f3"))
      )
      val s = TournamentImport.opponentSubmissions(exp, "me").head
      assertTrue(
        s.source == "tournament:t7",
        s.white == "Alice",
        s.black == "Bob",
        s.timeControl.contains("300+2"),
        s.moves.map(_.uci) == List("e2e4", "e7e5", "g1f3"),
        s.moves.forall(m => m.clockMs.isEmpty && m.emtMs.isEmpty)
      )
    },
    test("empty when every game involves us") {
      val exp = AnalyticsExport(
        "t1",
        clock,
        List(game("g1", "me", "pichess", "x", "SF", Some("white"), "e2e4"))
      )
      assertTrue(TournamentImport.opponentSubmissions(exp, "me").isEmpty)
    },
    test("toArchiveRecord builds the ladder, game ids + metadata") {
      val exp = AnalyticsExport(
        tournamentId = "t9",
        clock = clock,
        games = List(
          game("g1", "a", "Alice", "b", "Bob", Some("white"), "e2e4"),
          game("g2", "b", "Bob", "c", "Carol", Some("draw"), "d2d4")
        ),
        format = "swiss",
        finishedAt = Some("2026-06-28T12:00:00Z"),
        standings = List(
          AnalyticsExportStanding(
            1, "a", "Alice", Some("piChess"), Some("NNUE+HCE hybrid"),
            Some("weights-v8+nnue-v1"), 2.0, 2, 0, 0, 4.0
          ),
          AnalyticsExportStanding(
            2, "b", "Bob", None, None, None, 1.0, 1, 0, 1, 2.0
          )
        )
      )
      val rec = TournamentImport.toArchiveRecord(exp, "Summer Cup")
      assertTrue(
        rec.tournamentId == "t9",
        rec.name == "Summer Cup",
        rec.format == "swiss",
        rec.finishedAt > 0L, // ISO timestamp parsed to epoch millis
        rec.gameIds == List("g1", "g2"),
        rec.standings.map(_.botName) == List("Alice", "Bob"),
        rec.standings.head.rank == 1,
        rec.standings.head.engineType.contains("NNUE+HCE hybrid")
      )
    }
  )

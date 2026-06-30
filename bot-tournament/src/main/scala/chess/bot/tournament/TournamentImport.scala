package chess.bot.tournament

import chess.repository.api.{
  ArchiveSubmissionDto,
  SubmittedMoveDto,
  TournamentArchiveDto,
  TournamentStandingDto
}

/** Turns a finished tournament's analytics-export into per-game archive
  * submissions for the games we did NOT play.
  *
  * Our own games are already archived live by [[TournamentRecorder]] (and richer
  * — they carry per-move clocks the export omits), so we skip them here to avoid
  * overwriting the good copies with clock-less ones. The result is shipped to
  * the repository by [[TournamentBridge]] when a tournament ends, so the WHOLE
  * event — every game, not just ours — is browsable and analysable afterwards.
  */
object TournamentImport:

  /** Archive submissions for every game in `export` that `myId` did not play.
    * Games are tagged `source = "tournament:<id>"` so they can be grouped back
    * into their tournament for browsing.
    */
  def opponentSubmissions(
      exp: TournamentApiClient.AnalyticsExport,
      myId: String
  ): List[ArchiveSubmissionDto] =
    val timeControl = Some(s"${exp.clock.limit}+${exp.clock.increment}")
    exp.games
      .filter(g => g.whiteBotId != myId && g.blackBotId != myId)
      .map { g =>
        ArchiveSubmissionDto(
          gameId = g.gameId,
          source = s"tournament:${exp.tournamentId}",
          white = g.whiteBotName,
          black = g.blackBotName,
          result = resultToken(g.winner),
          timeControl = timeControl,
          moves = g.moves
            .split(' ')
            .iterator
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(uci => SubmittedMoveDto(uci, None, None))
            .toList
        )
      }

  /** The tournament-level record (ladder + game ids + metadata) for the history
    * archive. `name` comes from the tournament info (the export omits it). */
  def toArchiveRecord(
      exp: TournamentApiClient.AnalyticsExport,
      name: String
  ): TournamentArchiveDto =
    TournamentArchiveDto(
      tournamentId = exp.tournamentId,
      name = name,
      format = exp.format,
      finishedAt = exp.finishedAt.flatMap(parseEpochMillis).getOrElse(0L),
      standings = exp.standings.map(s =>
        TournamentStandingDto(
          rank = s.rank,
          botId = s.botId,
          botName = s.botName,
          family = s.botFamily,
          engineType = s.engineType,
          modelVersion = s.modelVersion,
          points = s.points,
          wins = s.wins,
          draws = s.draws,
          losses = s.losses,
          tieBreak = s.tieBreak
        )
      ),
      gameIds = exp.games.map(_.gameId)
    )

  /** Best-effort ISO-8601 → epoch millis (for history ordering); None if the
    * server's timestamp isn't parseable. */
  private def parseEpochMillis(iso: String): Option[Long] =
    scala.util.Try(java.time.Instant.parse(iso).toEpochMilli).toOption

  /** PGN result token from the export's `winner` ("white" / "black" / "draw");
    * `*` for an unknown/unterminated game (shouldn't occur for a finished
    * tournament, but kept total). */
  private def resultToken(winner: Option[String]): String =
    winner match
      case Some("white") => "1-0"
      case Some("black") => "0-1"
      case Some("draw")  => "1/2-1/2"
      case _             => "*"

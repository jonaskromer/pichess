package chess.gatling

/** Pulls baseURL + load-shape parameters from system properties so the same
  * simulation binary can drive different backends (PICHESS_BACKEND swap)
  * and different intensities without recompiling.
  *
  * Override on the command line:
  *   sbt -DpichessGatewayUrl=http://localhost:8090 \
  *       -DpichessLobbyUrl=http://localhost:8092 \
  *       -DpichessUsers=20 -DpichessRampSeconds=10 \
  *       -DpichessPeakUsers=200 -DpichessHoldSeconds=120 \
  *       -DpichessRatePerSec=5 \
  *       'gatling/Gatling/test'
  */
object SharedConfig:

  val gatewayUrl: String =
    sys.props.getOrElse("pichessGatewayUrl", "http://localhost:8090")

  val lobbyUrl: String =
    sys.props.getOrElse("pichessLobbyUrl", "http://localhost:8092")

  /** Repository service base URL — fronts the archive store (mongo/redis). Used
    * by the archive write/read simulation that isolates the persisted-game path
    * the gameplay flow only reaches indirectly via Kafka.
    */
  val repositoryUrl: String =
    sys.props.getOrElse("pichessRepositoryUrl", "http://localhost:8091")

  /** Analytics service base URL — the Kafka-fed read model surfaced over HTTP. */
  val analyticsUrl: String =
    sys.props.getOrElse("pichessAnalyticsUrl", "http://localhost:8093")

  /** Closed-loop user count for simple ramp simulations (e.g. the smoke
    * `GameSimulation` / `LobbySimulation`). Each user runs the scenario
    * once and exits.
    */
  val users: Int =
    sys.props.get("pichessUsers").flatMap(_.toIntOption).getOrElse(10)

  /** Duration (seconds) over which the closed-loop users are introduced. */
  val rampSeconds: Int =
    sys.props.get("pichessRampSeconds").flatMap(_.toIntOption).getOrElse(5)

  /** Peak concurrent / burst user count for stress, spike and volume
    * simulations.
    */
  val peakUsers: Int =
    sys.props.get("pichessPeakUsers").flatMap(_.toIntOption).getOrElse(50)

  /** Hold duration (seconds) for the plateau after the ramp in stress
    * mode, or the constant-rate window in endurance mode.
    */
  val holdSeconds: Int =
    sys.props.get("pichessHoldSeconds").flatMap(_.toIntOption).getOrElse(60)

  /** Open-loop arrival rate (users per second) for endurance / steady
    * background traffic between spikes.
    */
  val ratePerSec: Int =
    sys.props.get("pichessRatePerSec").flatMap(_.toIntOption).getOrElse(5)

  /** A short legal opening sequence in coordinate notation that every
    * scenario can replay regardless of backend or starting state.
    */
  val openingMoves: List[String] =
    List(
      "e2 e4",
      "e7 e5",
      "g1 f3",
      "b8 c6",
      "f1 b5",
      "a7 a6",
      "b5 a4",
      "g8 f6"
    )

  /** The same opening in bare UCI (no space) — the format the repository's
    * `POST /archives` (`SubmittedMoveDto.uci`) replays to rebuild SAN/PGN. Kept
    * in lock-step with [[openingMoves]].
    */
  val openingMovesUci: List[String] =
    openingMoves.map(_.filterNot(_.isWhitespace))

  /** A pre-built `ArchiveSubmissionDto` JSON body for one finished game, with a
    * `#{gameId}` placeholder Gatling fills per virtual user so each submission
    * is a distinct upsert (realistic archive-write fan-out, not a hot key).
    */
  val archiveSubmissionBody: String =
    val moves = openingMovesUci
      .map(uci => s"""{"uci":"$uci","clockMs":null,"emtMs":null}""")
      .mkString(",")
    s"""{"gameId":"#{gameId}","source":"local","white":"alice","black":"bob",""" +
      s""""result":"1-0","timeControl":null,"moves":[$moves]}"""

  /** Search depth for the `POST /api/analyze` load. Analyze runs ≈2 searches per
    * ply, so this is the dominant cost knob — bump it to find the CPU ceiling,
    * lower it for a faster smoke. The server clamps to [1, 20].
    */
  val analyzeDepth: Int =
    sys.props.get("pichessAnalyzeDepth").flatMap(_.toIntOption).getOrElse(6)

  /** The NowChess tournament id to spectate (set by `scripts/tournament-seed.sh`
    * after it stands up a real tournament on `../tournament-server`). Empty until
    * seeded — the tournament-spectate simulation only makes sense once it's set.
    */
  val tournamentId: String =
    sys.props.getOrElse("pichessTournamentId", "")

  /** The tournament gameIds to spectate (comma-separated), from the seed script.
    * The simulation spreads virtual users across these — several users on the
    * same game share one mirror (SSE fan-out), users on different games create
    * distinct mirrors (M concurrent followers polling the tournament server).
    */
  val spectateGameIds: List[String] =
    sys.props
      .get("pichessSpectateGameIds")
      .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList)
      .getOrElse(Nil)

  /** How long each virtual spectator holds its mirror SSE stream open (seconds).
    * Mirrors the real "watch for a while" behaviour the fan-out is sized for.
    */
  val spectateHoldSeconds: Int =
    sys.props.get("pichessSpectateHoldSeconds").flatMap(_.toIntOption).getOrElse(10)

  /** PGN movetext (no result token) of increasing length for the analyze load —
    * prefixes of the opening the gameplay sim plays, so they're provably legal.
    * The simulation appends a unique nonce comment + `*` per request so every
    * call is a distinct cache key (a real engine compute, not a cache hit). Cost
    * scales with ply count; the 8-ply line is the heavy one.
    */
  val analysisPgns: List[String] =
    List(
      "1. e4 e5",
      "1. e4 e5 2. Nf3 Nc6",
      "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6",
      // A 20-ply game that reaches a real middlegame — analyze cost on these
      // explodes with depth (≈24x per +2 ply, blows the server's 57s budget at
      // d8), unlike the cheap opening prefixes above. Include it so the sim
      // exercises realistic analyze cost, not just quiet openings.
      "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. d3 d6 6. c3 g6 7. h3 Bg7 8. Nbd2 Nd7 9. Nf1 Nc5 10. Bc2"
    )

package chess.bot.tournament

import zio.*
import zio.metrics.{Metric, MetricKeyType}

import chess.model.piece.Color

/** Prometheus metrics for tournament (bot-vs-bot) play, exposed on
  * `/metrics:9107` via the shared `chess.obs.MetricsHttpServer` and scraped
  * into Grafana (the `piChess — tournament` dashboard).
  *
  * All labels are bounded: `opponent` (a handful of bots), `color` (2),
  * `result` (3), `status` (~6 termination reasons), `family` / `move` (the
  * opening vocabulary). This file is metric-emission glue (coverage-excluded);
  * the pure classification lives in the tested [[GameOutcome]] / [[Openings]].
  */
object TournamentMetrics:

  private val thinkBoundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 20).map(_.toDouble)
    )
  private val lengthBoundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(10, 20, 30, 40, 60, 80, 120, 160, 240).map(_.toDouble)
    )

  private def colorName(c: Color): String = c match
    case Color.White => "white"
    case Color.Black => "black"

  // ── games, outcomes, openings, moves ──────────────────────────────
  private def startedC(opponent: String, color: String) =
    Metric
      .counter("pichess_tournament_games_started_total")
      .tagged("opponent", opponent)
      .tagged("color", color)
  private def finishedC(
      opponent: String,
      color: String,
      result: String,
      status: String
  ) =
    Metric
      .counter("pichess_tournament_games_finished_total")
      .tagged("opponent", opponent)
      .tagged("color", color)
      .tagged("result", result)
      .tagged("status", status)
  private def openingC(opponent: String, family: String) =
    Metric
      .counter("pichess_tournament_openings_total")
      .tagged("opponent", opponent)
      .tagged("family", family)
  private def firstMoveC(move: String) =
    Metric.counter("pichess_tournament_first_move_total").tagged("move", move)
  private def movesC(color: String) =
    Metric.counter("pichess_tournament_moves_total").tagged("color", color)

  def gameStarted(opponent: String, color: Color): UIO[Unit] =
    startedC(opponent, colorName(color)).increment
  def gameFinished(
      opponent: String,
      color: Color,
      outcome: GameOutcome.Outcome
  ): UIO[Unit] =
    finishedC(opponent, colorName(color), outcome.result, outcome.status).increment
  def opening(opponent: String, family: String): UIO[Unit] =
    openingC(opponent, family).increment
  def firstMove(move: String): UIO[Unit] = firstMoveC(move).increment
  def moveObserved(mover: Color): UIO[Unit] = movesC(colorName(mover)).increment

  // ── clocks, think-time, budget, game length ───────────────────────
  private def clockGauge(color: String) =
    Metric
      .gauge("pichess_tournament_clock_remaining_seconds")
      .tagged("color", color)
  private def thinkHist(color: String) =
    Metric
      .histogram("pichess_tournament_think_seconds", thinkBoundaries)
      .tagged("color", color)
  private val budgetHist =
    Metric.histogram("pichess_tournament_move_budget_seconds", thinkBoundaries)
  private val lengthHist =
    Metric.histogram("pichess_tournament_game_length_plies", lengthBoundaries)

  def clocks(clock: GameClock): UIO[Unit] =
    clockGauge("white").set(clock.whiteTime) *>
      clockGauge("black").set(clock.blackTime)
  def thinkTime(color: Color, seconds: Double): UIO[Unit] =
    thinkHist(colorName(color)).update(seconds)
  def budgetSeconds(seconds: Double): UIO[Unit] = budgetHist.update(seconds)
  def gameLength(plies: Int): UIO[Unit] = lengthHist.update(plies.toDouble)

  // ── live gauges & health counters ─────────────────────────────────
  private val activeGamesGauge = Metric.gauge("pichess_tournament_active_games")
  private val activeTournamentsGauge =
    Metric.gauge("pichess_tournament_active_tournaments")
  private val reconnectsC =
    Metric.counter("pichess_tournament_stream_reconnects_total")
  private val moveFailuresC =
    Metric.counter("pichess_tournament_move_failures_total")
  private val noMoveC =
    Metric.counter("pichess_tournament_search_no_move_total")

  // Process-wide live game count backing the gauge (ZIO-native unsafe Ref —
  // this is excluded glue, shared across the per-game daemon fibers).
  private val liveGames: Ref[Int] =
    Unsafe.unsafe(implicit u => Ref.unsafe.make(0))

  def gameOpened: UIO[Unit] =
    liveGames.updateAndGet(_ + 1).flatMap(n => activeGamesGauge.set(n.toDouble))
  def gameClosed: UIO[Unit] =
    liveGames
      .updateAndGet(n => math.max(0, n - 1))
      .flatMap(n => activeGamesGauge.set(n.toDouble))
  def activeTournaments(n: Int): UIO[Unit] =
    activeTournamentsGauge.set(n.toDouble)
  def reconnect: UIO[Unit] = reconnectsC.increment
  def moveFailed: UIO[Unit] = moveFailuresC.increment
  def searchNoMove: UIO[Unit] = noMoveC.increment

package chess.analytics

import zio.*
import zio.metrics.{Metric, MetricKeyType}

import chess.api.AnalyticsSummaryDto

/** Domain metrics for the Grafana dashboard. zio-metrics counters/gauges/
  * histograms flow through the obs `MetricsLayer` Prometheus connector to
  * `/metrics:9106` (already scraped) → Grafana panels, no extra wiring.
  *
  * Split by source: raw per-event rates/classifiers (moves, captures, checks,
  * castles, promotions, takebacks, active games) come from `chess.game-events`;
  * completed-game aggregates (outcomes, openings, ECO families, first move,
  * length/duration/think-time histograms, records) come from the Spark-
  * sessionized `chess.analytics` summaries. This file is metric-emission glue
  * (coverage-excluded); the classification logic lives in the pure, tested
  * `MoveFeatures`/`Eco`/`Records`/`AnalyticsState`.
  */
object AnalyticsMetrics:

  // ---- raw chess.game-events ------------------------------------------------

  private val moves        = Metric.counter("pichess_moves_total")
  private val captures     = Metric.counter("pichess_captures_total")
  private val checks       = Metric.counter("pichess_checks_total")
  private val checkmates   = Metric.counter("pichess_checkmates_total")
  private val promotions   = Metric.counter("pichess_promotions_total")
  private val gamesStarted = Metric.counter("pichess_games_started_total")
  private val takebacks    = Metric.counter("pichess_takebacks_total")
  private val activeGauge  = Metric.gauge("pichess_active_games")

  private def castle(side: String) =
    Metric.counter("pichess_castles_total").tagged("side", side)
  private def ending(kind: String) =
    Metric.counter("pichess_game_endings_total").tagged("type", kind)
  private def drawReason(reason: String) =
    Metric.counter("pichess_draw_reasons_total").tagged("reason", reason)

  /** One MoveMade: bump move counters per its SAN features. */
  def moveMade(san: String): UIO[Unit] =
    moves.increment *>
      ZIO.when(MoveFeatures.isCapture(san))(captures.increment) *>
      ZIO.when(MoveFeatures.isCheck(san))(checks.increment) *>
      ZIO.when(MoveFeatures.isCheckmate(san))(checkmates.increment) *>
      ZIO.when(MoveFeatures.isPromotion(san))(promotions.increment) *>
      ZIO.when(MoveFeatures.isKingsideCastle(san))(castle("kingside").increment) *>
      ZIO.when(MoveFeatures.isQueensideCastle(san))(castle("queenside").increment).unit

  val gameStarted: UIO[Unit]      = gamesStarted.increment
  val takeback: UIO[Unit]         = takebacks.increment
  def gameEnded(kind: String): UIO[Unit] = ending(kind).increment
  def drawClaimed(reason: String): UIO[Unit] =
    ending("DrawClaimed").increment *> drawReason(reason).increment
  def setActive(n: Int): UIO[Unit] = activeGauge.set(n.toDouble)

  // ---- completed-game aggregates (chess.analytics summaries) ----------------

  private val gamesCompleted = Metric.counter("pichess_games_completed_total")
  private val completedMoves = Metric.counter("pichess_completed_moves_total")

  private def byOutcome(o: String) =
    Metric.counter("pichess_games_by_outcome_total").tagged("outcome", o)
  private def byOpening(op: String) =
    Metric.counter("pichess_opening_games_total").tagged("opening", op)
  private def byEco(fam: String) =
    Metric.counter("pichess_opening_family_total").tagged("family", fam)
  private def byFirstMove(m: String) =
    Metric.counter("pichess_first_move_total").tagged("move", m)

  private val movesPerGame =
    Metric.histogram(
      "pichess_game_length_moves",
      MetricKeyType.Histogram.Boundaries.fromChunk(
        Chunk(5, 10, 20, 30, 40, 60, 80, 120, 200).map(_.toDouble)
      )
    )
  private val gameSeconds =
    Metric.histogram(
      "pichess_game_duration_seconds",
      MetricKeyType.Histogram.Boundaries.fromChunk(
        Chunk(5, 15, 30, 60, 120, 300, 600, 1800).map(_.toDouble)
      )
    )
  private val thinkSeconds =
    Metric.histogram(
      "pichess_think_seconds_per_move",
      MetricKeyType.Histogram.Boundaries.fromChunk(
        Chunk(0.5, 1, 2, 3, 5, 10, 20, 60).map(_.toDouble)
      )
    )

  private val longestGauge  = Metric.gauge("pichess_record_longest_game_moves")
  private val shortestGauge = Metric.gauge("pichess_record_shortest_game_moves")
  private val capturesGauge = Metric.gauge("pichess_record_most_captures")

  def gameCompleted(s: AnalyticsSummaryDto): UIO[Unit] =
    val opening   = if s.opening.isEmpty then "(no moves)" else s.opening
    val outcome   = if s.outcome.isEmpty then s.result else s.outcome
    val firstMove = s.opening.split(' ').headOption.filter(_.nonEmpty).getOrElse("(none)")
    gamesCompleted.increment *>
      completedMoves.incrementBy(s.totalMoves.toLong) *>
      byOutcome(outcome).increment *>
      byOpening(opening).increment *>
      byEco(Eco.familyOf(s.opening)).increment *>
      byFirstMove(firstMove).increment *>
      movesPerGame.update(s.totalMoves.toDouble) *>
      gameSeconds.update(s.durationMs.toDouble / 1000.0) *>
      thinkSeconds.update(s.avgThinkTimeMs / 1000.0)

  /** Publish the current leaderboard records as gauges. */
  def setRecords(r: Records): UIO[Unit] =
    longestGauge.set(r.longestGameMoves.toDouble) *>
      shortestGauge.set(r.shortestGameMoves.toDouble) *>
      capturesGauge.set(r.mostCaptures.toDouble)

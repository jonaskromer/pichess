package chess.spark

import scala3encoders.given

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.agg.DomainAggregations
import chess.spark.agg.DomainAggregations.GameStat
import chess.spark.schema.{EventDecoding, MoveEventRow}

/** Batch entrypoint for the domain-rich analytics: a FEN-derived square
  * occupancy heatmap, opening → outcome distribution, and think-time stats —
  * over the archived `chess.game-events` dump.
  */
object DomainStatsMain extends ZIOAppDefault:

  private val eventsPath = "spark-analytics/data/events/*.json"

  private def readRows(path: String): SIO[Dataset[MoveEventRow]] =
    SparkSession.read
      .textFile(path)
      .map(_.flatMap(line => EventDecoding.parseRow(line).toOption))

  /** Render an 8×8 ASCII heatmap (ranks 8→1, files a→h) from square counts. */
  private def renderHeatmap(counts: Map[String, Long]): String =
    val ranks =
      for rank <- 8 to 1 by -1 yield
        val cells =
          for file <- 'a' to 'h' yield
            f"${counts.getOrElse(s"$file$rank", 0L)}%3d"
        s"$rank " + cells.mkString(" ")
    val footer = "    " + ('a' to 'h').map(f => f"  $f").mkString(" ")
    (ranks :+ footer).mkString("\n")

  private def job: ZIO[SparkSession, Throwable, Unit] =
    for
      rows    <- readRows(eventsPath)
      occ     <- DomainAggregations.squareOccupancy(rows).collect
      stats   <- DomainAggregations.gameStats(rows).collect
      outcomes <- DomainAggregations.openingOutcomes(
                    DomainAggregations.gameStats(rows)
                  ).collect
      occMap   = occ.toMap
      _       <- Console.printLine("[spark-analytics] square occupancy heatmap (MoveMade positions):")
      _       <- Console.printLine(renderHeatmap(occMap))
      _       <- Console.printLine("\n[spark-analytics] opening → outcome (games):")
      _       <- ZIO.foreachDiscard(outcomes.sortBy(-_._2)) { case ((opening, outcome), n) =>
                   Console.printLine(f"  $n%2d  [$outcome%-10s] $opening")
                 }
      _       <- Console.printLine("\n[spark-analytics] per-game think-time:")
      _       <- ZIO.foreachDiscard(stats.sortBy(_.gameId)) { s =>
                   Console.printLine(
                     f"  ${s.gameId}%-10s moves=${s.moves}%2d outcome=${s.outcome}%-10s think=${s.thinkMs / 1000.0}%5.1fs/move"
                   )
                 }
      _       <- avgThink(stats)
    yield ()

  private def avgThink(stats: Seq[GameStat]): ZIO[Any, java.io.IOException, Unit] =
    val played = stats.filter(_.moves > 1)
    if played.isEmpty then ZIO.unit
    else
      val avg = played.map(_.thinkMs).sum / played.size / 1000.0
      Console.printLine(f"  overall avg think-time: $avg%.1fs/move over ${played.size} games")

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-domain-stats")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] = job.provide(session)

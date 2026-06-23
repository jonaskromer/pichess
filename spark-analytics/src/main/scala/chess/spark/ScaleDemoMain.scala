package chess.spark

import scala3encoders.given

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.agg.{Aggregations, DomainAggregations}
import chess.spark.scale.ScaleGen
import chess.spark.schema.MoveEventRow

/** Scale demo — generate a large synthetic event set in-cluster and crunch it,
  * reporting per-aggregation wall-clock. Shows Spark's parallelism and the
  * effect of `spark.sql.shuffle.partitions`, tying into the course's
  * perf-testing thread.
  *
  * Tunables (env): `PICHESS_SCALE_GAMES` (default 50_000 → ~1.1M rows),
  * `PICHESS_SCALE_PARTITIONS` (default 8).
  */
object ScaleDemoMain extends ZIOAppDefault:

  private val numGames =
    sys.env.get("PICHESS_SCALE_GAMES").flatMap(_.toLongOption).getOrElse(50000L)
  private val parts =
    sys.env.get("PICHESS_SCALE_PARTITIONS").flatMap(_.toIntOption).getOrElse(8)

  /** Generate rows in-cluster via the underlying `SparkSession.range` so the
    * data never round-trips through the driver. */
  private def synthetic(n: Long): SIO[Dataset[MoveEventRow]] =
    fromSpark { ss =>
      ss.range(0, n)
        .flatMap((gid: java.lang.Long) => ScaleGen.gameRows(gid.longValue))
        .zioSpark
    }

  private def timed[A](label: String)(z: Task[A]): Task[A] =
    z.timed.flatMap { (d, a) =>
      Console.printLine(f"  $label%-26s ${d.toMillis}%7d ms").as(a)
    }

  private def job: ZIO[SparkSession, Throwable, Unit] =
    for
      _       <- fromSpark(_.conf.set("spark.sql.shuffle.partitions", parts.toString))
      raw     <- synthetic(numGames)
      rows    <- raw.repartition(parts).cache
      total   <- timed("generate + count rows")(rows.count)
      _       <- Console.printLine(
                   s"[spark-analytics] scale demo: games=$numGames rows=$total shufflePartitions=$parts"
                 )
      _       <- timed("eventTypeBreakdown")(Aggregations.eventTypeBreakdown(rows).collect)
      _       <- timed("openingPopularity top10")(Aggregations.openingPopularity(rows, 10).collect)
      _       <- timed("sessionize gameStats")(DomainAggregations.gameStats(rows).count)
      _       <- timed("squareOccupancy heatmap")(DomainAggregations.squareOccupancy(rows).collect)
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-scale-demo")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] = job.provide(session)

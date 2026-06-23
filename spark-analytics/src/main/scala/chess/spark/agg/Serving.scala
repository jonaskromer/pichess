package chess.spark.agg

import org.apache.spark.sql.functions.{col, sum}

import zio.spark.sql.*
import zio.spark.sql.TryAnalysis.syntax.throwAnalysisException

/** Lambda **serving layer** reconciliation. Both inputs are
  * `[eventType, count]` views — `batch` is the authoritative view recomputed
  * over the full archive (durable in Parquet), `speed` is the recent increment
  * from the streaming layer. The serving query is their union re-aggregated by
  * key, so a reader sees `batch ⊕ speed` without either layer alone being
  * complete.
  */
object Serving:

  def mergeEventCounts(batch: DataFrame, speed: DataFrame): DataFrame =
    batch
      .union(speed)
      .groupBy(col("eventType"))
      .agg(sum(col("count")).as("count"))

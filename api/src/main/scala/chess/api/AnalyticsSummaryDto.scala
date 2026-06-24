package chess.api

import zio.json.*

/** Wire DTO for one completed-game analytics summary, computed by the Spark
  * speed layer and published to the `chess.analytics` topic. Shared (like the
  * other DTOs in this module) so the gateway (JVM) and the web-ui (Scala.js)
  * agree on the shape: the gateway relays the raw JSON over SSE, the web-ui
  * decodes it with this codec to drive the live panel.
  *
  * Field-compatible with `chess.spark.session.GameSummary` (the Spark producer
  * lives in a separate Scala-3.3 module and can't be shared, so this is the
  * canonical wire contract both sides target).
  */
final case class AnalyticsSummaryDto(
    gameId: String,
    totalMoves: Int,
    captures: Int,
    durationMs: Long,
    opening: String,
    result: String,
    // How the game ended: winning colour ("White"/"Black"), "Draw", or a
    // GameEnded status — drives the outcome metrics/panels.
    outcome: String,
    avgThinkTimeMs: Double
)

object AnalyticsSummaryDto:
  given JsonCodec[AnalyticsSummaryDto] = DeriveJsonCodec.gen

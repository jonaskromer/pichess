package chess.spark.schema

/** LAYER 2 of the schema boundary — the flat, columnar projection that Spark
  * Datasets understand.
  *
  * The wire schema (LAYER 1) is the JSON of `chess.events.GameDomainEvent`,
  * decoded here through the standalone [[RawGameEvent]] mirror. We flatten it
  * into this single record — which deliberately matches the analytics-service
  * ClickHouse `move_events` table (`game_id, event_type, san, fen,
  * occurred_at`) so the Spark path and the existing consumer produce the same
  * shape.
  *
  * All fields are primitives/`String` (no `Option`, no nesting) to keep the
  * Scala 3 encoder derivation — provided by `spark-scala3` via
  * `scala3encoders.given` — on its happy path.
  */
final case class MoveEventRow(
    gameId: String,
    eventType: String,
    san: String,
    fen: String,
    occurredAt: Long
)

object MoveEventRow:

  /** Flatten one decoded event. `san` is only meaningful for `MoveMade`; every
    * other variant carries the empty string, matching the at-least-once
    * `move_events` projection in analytics-service.
    */
  def fromEvent(e: RawGameEvent): MoveEventRow =
    MoveEventRow(
      gameId = e.gameId,
      eventType = e.`type`,
      san = e.san.getOrElse(""),
      fen = e.resultingFen,
      occurredAt = e.occurredAt
    )

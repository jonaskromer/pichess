package chess.spark.schema

import zio.json.*

/** Standalone, minimal mirror of the fields this module needs from the
  * canonical `chess.events.GameDomainEvent` JSON on the `chess.game-events`
  * topic.
  *
  * Why re-declared instead of shared: the `events` module is compiled with
  * Scala 3.8.2, whose TASTy a Scala 3.3 compiler cannot read, and this module
  * must run on 3.3 (the last line with a genuine 2.13 stdlib, which Spark's
  * scala-reflect requires — see build.sbt). So the schema boundary is a flat
  * zio-json record here rather than a `dependsOn(events)`.
  *
  * zio-json ignores unknown fields by default, so decoding the discriminator
  * (`type`) plus the universal fields is enough; `san` is present only on
  * `MoveMade`, hence `Option`. The variant-specific extras (`moveCoord`,
  * `winner`, `reason`, `status`, `initialFen`, `historyMoves`) are simply
  * dropped. Keep this in sync with `chess.events.GameDomainEvent`.
  */
final case class RawGameEvent(
    `type`: String,
    gameId: String,
    resultingFen: String,
    san: Option[String],
    occurredAt: Long,
    // Terminal-event outcome fields (absent on non-terminal events → None).
    // `Forfeited` carries `winner`, `GameEnded` carries `status`.
    winner: Option[String] = None,
    status: Option[String] = None
)

object RawGameEvent:
  /** The topic these events flow on — mirrors `chess.events.Topics.GameEvents`. */
  val Topic: String = "chess.game-events"

  given JsonDecoder[RawGameEvent] = DeriveJsonDecoder.gen

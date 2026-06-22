package chess.spark.schema

import zio.json.*

/** The ingress half of the schema boundary: raw JSON (a Kafka value, or one
  * line of an archived event dump) decoded with the standalone [[RawGameEvent]]
  * zio-json codec, then flattened to [[MoveEventRow]].
  *
  * Plain pure functions (no Spark types) so they're trivially unit testable and
  * safe to call inside a Spark `map`/`flatMap` closure — the decoder is reached
  * statically through the `RawGameEvent` companion, never captured from
  * enclosing mutable state.
  */
object EventDecoding:

  /** Decode one JSON document into the standalone event mirror. */
  def decode(json: String): Either[String, RawGameEvent] =
    json.fromJson[RawGameEvent]

  /** Decode and flatten in one step; `Left` carries the zio-json error message
    * for the caller to count/log as a malformed-record metric.
    */
  def parseRow(json: String): Either[String, MoveEventRow] =
    decode(json).map(MoveEventRow.fromEvent)

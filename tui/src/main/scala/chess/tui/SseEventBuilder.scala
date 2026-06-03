package chess.tui

import zio.json.*

import chess.api.BoardStateDto

/** Pure SSE event-frame parser. Extracted out of [[TuiEventStream]] so the
  * state-machine transitions are unit-testable without standing up an HTTP
  * stream — the stream wiring in `TuiEventStream.subscribe` stays excluded
  * from coverage, but this builder is exercised line-for-line here.
  *
  * SSE wire format: lines of `event: <type>` and `data: <payload>`,
  * terminated by an empty line. Consecutive `data:` lines concatenate.
  * `id:` / `retry:` / comment lines are ignored — the gateway never emits
  * them.
  */
object SseEventBuilder:

  /** One parsed SSE event. */
  enum Event:
    /** A state push from the gateway. JSON already decoded. */
    case State(dto: BoardStateDto)

  /** Per-event builder. `dispatched` is set on each dispatch call so the
    * outer stream's `scan` can lift completed events back out — the next
    * `append` clears it again.
    */
  final case class Builder(
      eventType: Option[String],
      data: Vector[String],
      dispatched: Option[Either[String, Event]]
  ):
    def append(line: String): Builder =
      val cleared = copy(dispatched = None)
      val (k, v) = line.indexOf(':') match
        case -1  => (line, "")
        case idx =>
          (line.substring(0, idx), line.substring(idx + 1).stripPrefix(" "))
      k match
        case "event" => cleared.copy(eventType = Some(v))
        case "data"  => cleared.copy(data = cleared.data :+ v)
        case _       => cleared

    def dispatch: Builder =
      val payload = data.mkString("\n")
      val event = eventType match
        case Some("state") =>
          payload.fromJson[BoardStateDto] match
            case Right(dto) => Right(Event.State(dto))
            case Left(err)  => Left(s"state decode failed: $err")
        case Some(other)  => Left(s"unknown event type: $other")
        case None if data.isEmpty => Left("empty event")
        case None => Left("event without type")
      Builder.empty.copy(dispatched = Some(event))

  object Builder:
    val empty: Builder = Builder(None, Vector.empty, None)

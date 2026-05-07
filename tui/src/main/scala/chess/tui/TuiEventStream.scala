package chess.tui

import chess.api.BoardStateDto
import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.model.Uri
import zio.*
import zio.json.*
import zio.stream.*

/** Consumes the gateway's SSE stream at `/api/events` and emits the same
  * `BoardStateDto` snapshots the web-ui sees, so a TUI session running
  * alongside the GUI re-renders whenever the GUI mutates state.
  *
  * Built on sttp's `asStreamAlwaysUnsafe(ZioStreams)` so the response body
  * arrives as a `ZStream[Byte]` we can pipe through ZIO's stream operators
  * (UTF-8 decode + line split + scan-based SSE parser). An earlier version
  * used the JDK's `HttpClient.BodyHandlers.ofLines()` directly, but the
  * blocking iterator behind it didn't yield lines reliably under
  * `ZStream.fromJavaStream`, so events landed late or not at all.
  */
object TuiEventStream:

  /** One parsed SSE event. */
  enum Event:
    /** A state push from the gateway. JSON already decoded. */
    case State(dto: BoardStateDto)

  /** Subscribe to the SSE feed. Returns a stream of decoded events; decode
    * errors and malformed events are dropped (they're logged at warn).
    *
    * The HTTP connection is opened on first pull and torn down when the
    * consumer scope ends. Caller is expected to run the resulting stream
    * inside a scope (`forkScoped` does this for the TUI case).
    */
  def subscribe(
      baseUri: Uri,
      backend: SttpBackend[Task, ZioStreams],
      gameId: String
  ): ZStream[Any, Throwable, Event] =
    val request = basicRequest
      .get(baseUri.addPath("api", "games", gameId, "events"))
      .header("Accept", "text/event-stream")
      .header("Cache-Control", "no-cache")
      .response(asStreamAlwaysUnsafe(ZioStreams))

    ZStream
      .fromZIO(backend.send(request).map(_.body))
      .flatten
      .via(ZPipeline.utf8Decode)
      .via(ZPipeline.splitLines)
      .scan(Builder.empty) { (acc, line) =>
        if line.isEmpty then acc.dispatch else acc.append(line)
      }
      .collect { case b if b.dispatched.isDefined => b.dispatched.get }
      .collectZIO {
        case Right(event) => ZIO.some(event)
        case Left(err) =>
          ZIO.logWarning(s"SSE decode error: $err").as(None)
      }
      .collectSome

  /** Per-event builder. SSE wire format: lines of `event: <type>` and
    * `data: <payload>`, terminated by an empty line. Consecutive `data:`
    * lines concatenate. We don't handle `id:` / `retry:` / comment lines
    * (the gateway never emits them).
    */
  private final case class Builder(
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

  private object Builder:
    val empty: Builder = Builder(None, Vector.empty, None)

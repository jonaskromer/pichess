package chess.tui

import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.model.Uri
import zio.*
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

  /** Re-exported so existing callers keep their import path. The parser
    * itself lives in [[SseEventBuilder]] now.
    */
  export SseEventBuilder.Event

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
      .scan(SseEventBuilder.Builder.empty) { (acc, line) =>
        if line.isEmpty then acc.dispatch else acc.append(line)
      }
      .collect { case b if b.dispatched.isDefined => b.dispatched.get }
      .collectZIO {
        case Right(event) => ZIO.some(event)
        case Left(err) =>
          ZIO.logWarning(s"SSE decode error: $err").as(None)
      }
      .collectSome

package chess.bot.lichess

import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.model.{StatusCode, Uri}
import zio.*
import zio.json.*
import zio.stream.*

/** Thin client over the Lichess Bot API.
  *
  * The trait keeps the surface small: two streams (account events,
  * per-game events) and three mutating calls (accept a challenge, make
  * a move, resign). The Phase 2 bridge needs exactly this set — accept
  * → stream → play → resign-on-defect → done.
  *
  * Methods that mutate game state return `Unit`. Lichess responds with
  * `{"ok": true}` on success and a 4xx + `{"error": "..."}` on
  * failure; the sttp impl maps non-2xx to a failed effect so the
  * caller can `.catchAll` and decide whether to retry or abandon.
  */
trait BotApiClient:

  /** SSE-style NDJSON stream of account-level events: incoming
    * challenges, game starts, game finishes. The connection stays
    * open for the lifetime of the bot process; reconnects are the
    * caller's responsibility (a simple `.retry(Schedule.fixed(5.seconds))`
    * works fine). */
  def streamEvents: ZStream[Any, Throwable, AccountEvent]

  /** NDJSON stream of events for a single game in progress.
    *
    * The first event is always [[GameEvent.GameFull]]; subsequent
    * events are [[GameEvent.GameStateEvent]] / [[GameEvent.ChatLine]]
    * / [[GameEvent.OpponentGone]]. Stream completes on the server side
    * when the game ends. */
  def streamGame(gameId: String): ZStream[Any, Throwable, GameEvent]

  /** Accept a challenge by id. */
  def acceptChallenge(challengeId: String): IO[Throwable, Unit]

  /** POST a UCI move (e.g. "e2e4", "e7e8q") for the given game. */
  def makeMove(gameId: String, uci: String): IO[Throwable, Unit]

  /** Resign the given game. Used when the bot encounters an
    * unrecoverable state (search returned None at a non-terminal
    * position, malformed event payload, etc.). */
  def resign(gameId: String): IO[Throwable, Unit]

object BotApiClient:

  /** Connection settings. `token` is the Lichess personal access
    * token (env `LICHESS_BOT_TOKEN` is the convention); `baseUrl`
    * defaults to production but is configurable for tests against a
    * stub backend. */
  final case class Config(token: String, baseUrl: Uri = uri"https://lichess.org")

  /** Build a Lichess-talking client over an existing sttp backend. */
  def sttp(
      backend: SttpBackend[Task, ZioStreams],
      config: Config,
  ): BotApiClient =
    new SttpBotApiClient(backend, config)

  /** sttp-backed implementation. Builds requests with the bearer-token
    * auth header, streams NDJSON responses via `ZPipeline.splitLines`,
    * and decodes each JSON line through zio-json. The streaming
    * pattern mirrors `chess.tui.TuiEventStream` so the bridge fiber
    * lifecycle is the same as the TUI's.
    */
  private[lichess] final class SttpBotApiClient(
      backend: SttpBackend[Task, ZioStreams],
      config: Config,
  ) extends BotApiClient:

    private val auth = s"Bearer ${config.token}"

    def streamEvents: ZStream[Any, Throwable, AccountEvent] =
      ndjsonStream[AccountEvent](
        config.baseUrl.addPath("api", "stream", "event")
      )

    def streamGame(gameId: String): ZStream[Any, Throwable, GameEvent] =
      ndjsonStream[GameEvent](
        config.baseUrl.addPath("api", "bot", "game", "stream", gameId)
      )

    def acceptChallenge(challengeId: String): IO[Throwable, Unit] =
      postExpectOk(config.baseUrl.addPath("api", "challenge", challengeId, "accept"))

    def makeMove(gameId: String, uci: String): IO[Throwable, Unit] =
      postExpectOk(config.baseUrl.addPath("api", "bot", "game", gameId, "move", uci))

    def resign(gameId: String): IO[Throwable, Unit] =
      postExpectOk(config.baseUrl.addPath("api", "bot", "game", gameId, "resign"))

    /** Generic NDJSON streamer: pipe the raw response body through
      * UTF-8 decode → line split → drop blanks → decode JSON.
      * Malformed JSON lines fail the stream — Lichess's API is
      * stable enough that a parse error genuinely means something
      * upstream changed and the bot can't trust subsequent events. */
    private def ndjsonStream[A: JsonDecoder](endpoint: Uri)
        : ZStream[Any, Throwable, A] =
      val request = basicRequest
        .get(endpoint)
        .header("Authorization", auth)
        .response(asStreamAlwaysUnsafe(ZioStreams))
      ZStream
        .fromZIO(backend.send(request).map(_.body))
        .flatten
        .via(ZPipeline.utf8Decode)
        .via(ZPipeline.splitLines)
        .filter(_.nonEmpty)
        .mapZIO { line =>
          ZIO
            .fromEither(line.fromJson[A])
            .mapError(err => new RuntimeException(s"NDJSON decode failed: $err — line: $line"))
        }

    /** POST with no body, treat a 2xx as success. Lichess returns
      * `{"ok": true}` on accept / move / resign — we don't need to
      * inspect the body, the status code is enough. */
    private def postExpectOk(endpoint: Uri): IO[Throwable, Unit] =
      val request = basicRequest
        .post(endpoint)
        .header("Authorization", auth)
        .response(asStringAlways)
      backend.send(request).flatMap { response =>
        if response.code.isSuccess then ZIO.unit
        else ZIO.fail(BotApiError(response.code, response.body))
      }

/** Raised when Lichess responds with a non-2xx status. The body is
  * usually a `{"error": "..."}` payload but we keep it raw so the
  * caller can log whatever Lichess actually said. */
final case class BotApiError(status: StatusCode, body: String)
    extends RuntimeException(s"Lichess responded $status: $body")

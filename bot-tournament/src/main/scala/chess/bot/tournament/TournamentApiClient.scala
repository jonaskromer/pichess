package chess.bot.tournament

import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.model.{StatusCode, Uri}
import zio.*
import zio.json.*
import zio.stream.*

/** Thin client over the NowChess tournament server's REST + NDJSON API.
  *
  * Mirrors the Lichess `BotApiClient` in spirit: two NDJSON streams (tournament
  * events, per-game events) plus the handful of mutating / lookup calls the
  * bridge needs — register, list/get tournament, join, make a move.
  *
  * Auth is a JWT obtained at runtime from [[register]] (no pre-issued token),
  * so the client holds the bearer token in a `Ref` that `register` populates;
  * every authenticated call reads it. The token has no expiry and `register` is
  * idempotent by `(name, isBot)`, so a restart just re-registers the same name
  * and keeps playing as the same bot. See `docs/tournament-integration.md`.
  */
trait TournamentApiClient:

  /** `POST /api/auth/register` with `isBot:true`. Stores the returned JWT for
    * subsequent authenticated calls and returns the `{id, token}` for logging.
    */
  def register(name: String): IO[Throwable, TournamentApiClient.RegisterResult]

  /** `GET /api/tournament` → the `created` (joinable) tournaments. Public. */
  def listTournaments: IO[Throwable, List[TournamentApiClient.TournamentInfo]]

  /** `GET /api/tournament/{id}` → the tournament (we only read its clock, for
    * the increment that the per-game events omit). Public.
    */
  def getTournament(
      id: String
  ): IO[Throwable, TournamentApiClient.TournamentInfo]

  /** `GET /api/tournament/{id}/game/{gameId}` → the game's players. Needed
    * because `gameStart` broadcasts BOTH colours for EVERY game to every
    * subscriber, and the per-game stream carries no player ids — so the only
    * way to learn whether (and as which colour) we're in a game is to match our
    * registered id against `white`/`black` here. Public.
    */
  def getGame(
      id: String,
      gameId: String
  ): IO[Throwable, TournamentApiClient.GamePlayers]

  /** `POST /api/tournament/{id}/join` — only valid while status is `created`.
    */
  def joinTournament(id: String): IO[Throwable, Unit]

  /** NDJSON stream of tournament events (round lifecycle + `gameStart`). */
  def streamTournament(id: String): ZStream[Any, Throwable, TournamentEvent]

  /** NDJSON stream of per-game events (snapshot, then move / end). */
  def streamGame(id: String, gameId: String): ZStream[Any, Throwable, GameEvent]

  /** `POST /api/tournament/{id}/game/{gameId}/move/{uci}` — UCI in the path. */
  def makeMove(id: String, gameId: String, uci: String): IO[Throwable, Unit]

object TournamentApiClient:

  /** Connection settings. `baseUrl` defaults to production but is configurable
    * (staging / `http://localhost:8086` / tests).
    */
  final case class Config(
      baseUrl: Uri = uri"https://nowchess.janis-eccarius.de"
  )

  /** `POST /api/auth/register` response: the assigned id (JWT subject) + token.
    */
  final case class RegisterResult(id: String, token: String)
  object RegisterResult:
    given JsonDecoder[RegisterResult] = DeriveJsonDecoder.gen[RegisterResult]

  /** Minimal projection of the (large, flattened) tournament JSON — we only
    * need the id and clock. zio-json ignores the many other fields.
    */
  final case class TournamentInfo(id: String, clock: TournamentClock)
  object TournamentInfo:
    given JsonDecoder[TournamentInfo] = DeriveJsonDecoder.gen[TournamentInfo]

  /** `GET /api/tournament` envelope; we only consume `created`. */
  private final case class TournamentList(created: List[TournamentInfo])
  private object TournamentList:
    given JsonDecoder[TournamentList] = DeriveJsonDecoder.gen[TournamentList]

  /** Projection of the game JSON — we only need the two players' ids to work
    * out our colour. zio-json ignores the other fields (fen, moves, …).
    */
  final case class GamePlayers(white: BotRef, black: BotRef)
  object GamePlayers:
    given JsonDecoder[GamePlayers] = DeriveJsonDecoder.gen[GamePlayers]

  /** Build a NowChess-talking client over an existing sttp backend. Effectful
    * because it allocates the token `Ref`.
    */
  def sttp(
      backend: SttpBackend[Task, ZioStreams],
      config: Config
  ): UIO[TournamentApiClient] =
    Ref.make("").map(new SttpTournamentApiClient(backend, config, _))

  /** sttp-backed implementation. Same NDJSON + `postExpectOk` patterns as the
    * Lichess client, but auth is read from the runtime-populated token `Ref`.
    */
  private[tournament] final class SttpTournamentApiClient(
      backend: SttpBackend[Task, ZioStreams],
      config: Config,
      tokenRef: Ref[String]
  ) extends TournamentApiClient:

    private def authHeader: UIO[String] = tokenRef.get.map(t => s"Bearer $t")

    def register(name: String): IO[Throwable, RegisterResult] =
      val body = s"""{"name":${name.toJson},"isBot":true}"""
      val request = basicRequest
        .post(config.baseUrl.addPath("api", "auth", "register"))
        .header("Content-Type", "application/json")
        .body(body)
        .response(asStringAlways)
      backend.send(request).flatMap { response =>
        if response.code.isSuccess then
          ZIO
            .fromEither(response.body.fromJson[RegisterResult])
            .mapError(err =>
              new RuntimeException(
                s"register decode failed: $err — body: ${response.body}"
              )
            )
            .tap(r => tokenRef.set(r.token))
        else ZIO.fail(TournamentApiError(response.code, response.body))
      }

    def listTournaments: IO[Throwable, List[TournamentInfo]] =
      getJson[TournamentList](config.baseUrl.addPath("api", "tournament"))
        .map(_.created)

    def getTournament(id: String): IO[Throwable, TournamentInfo] =
      getJson[TournamentInfo](config.baseUrl.addPath("api", "tournament", id))

    def getGame(id: String, gameId: String): IO[Throwable, GamePlayers] =
      getJson[GamePlayers](
        config.baseUrl.addPath("api", "tournament", id, "game", gameId)
      )

    def joinTournament(id: String): IO[Throwable, Unit] =
      postExpectOk(config.baseUrl.addPath("api", "tournament", id, "join"))

    def makeMove(id: String, gameId: String, uci: String): IO[Throwable, Unit] =
      postExpectOk(
        config.baseUrl
          .addPath("api", "tournament", id, "game", gameId, "move", uci)
      )

    def streamTournament(id: String): ZStream[Any, Throwable, TournamentEvent] =
      ndjsonStream[TournamentEvent](
        config.baseUrl.addPath("api", "tournament", id, "stream")
      )

    def streamGame(
        id: String,
        gameId: String
    ): ZStream[Any, Throwable, GameEvent] =
      ndjsonStream[GameEvent](
        config.baseUrl
          .addPath("api", "tournament", id, "game", gameId, "stream")
      )

    /** GET a JSON document (public endpoints — no auth needed). */
    private def getJson[A: JsonDecoder](endpoint: Uri): IO[Throwable, A] =
      val request = basicRequest.get(endpoint).response(asStringAlways)
      backend.send(request).flatMap { response =>
        if response.code.isSuccess then
          ZIO
            .fromEither(response.body.fromJson[A])
            .mapError(err =>
              new RuntimeException(
                s"decode failed for $endpoint: $err — body: ${response.body}"
              )
            )
        else ZIO.fail(TournamentApiError(response.code, response.body))
      }

    /** Generic NDJSON streamer: bearer auth, UTF-8 decode → line split → drop
      * blanks → decode JSON. A malformed line fails the stream — the bridge
      * wraps stream consumption in a reconnect retry.
      */
    private def ndjsonStream[A: JsonDecoder](
        endpoint: Uri
    ): ZStream[Any, Throwable, A] =
      ZStream.fromZIO(authHeader).flatMap { auth =>
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
              .mapError(err =>
                new RuntimeException(
                  s"NDJSON decode failed: $err — line: $line"
                )
              )
          }
      }

    /** Authenticated POST with no body; treat any 2xx as success
      * (`{"ok":true}`), surface non-2xx as [[TournamentApiError]].
      */
    private def postExpectOk(endpoint: Uri): IO[Throwable, Unit] =
      authHeader.flatMap { auth =>
        val request = basicRequest
          .post(endpoint)
          .header("Authorization", auth)
          .response(asStringAlways)
        backend.send(request).flatMap { response =>
          if response.code.isSuccess then ZIO.unit
          else ZIO.fail(TournamentApiError(response.code, response.body))
        }
      }

/** Raised when the tournament server responds with a non-2xx status. The body
  * is usually a `{"error": "..."}` payload, kept raw for logging.
  */
final case class TournamentApiError(status: StatusCode, body: String)
    extends RuntimeException(s"Tournament server responded $status: $body")

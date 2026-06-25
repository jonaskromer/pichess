package chess.api

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*

/** Typed endpoint descriptions for the pichess HTTP API.
  *
  * Each value is a Tapir `Endpoint` that declares method, path, request, and
  * response types. The gateway interprets these as zio-http routes; future
  * clients (web-ui, inter-service callers) can interpret the same values as
  * typed Sttp clients. That's the whole point of keeping them in the shared
  * `api` module — adding or changing an endpoint is a compile-breaking change
  * on both sides until they agree.
  *
  * **Routing**: every game endpoint is scoped under `/api/games/{id}/...` so
  * multiple games can run side-by-side. The single un-scoped exception is `POST
  * /api/games`, which mints a new game (with optional `load` body) and returns
  * its id. The previous `POST /api/new` and `POST /api/load` collapsed into
  * that one shape.
  */
object Endpoints:

  /** Tapir alias for the path's gameId segment — keeps the type signatures
    * legible (the codec module already aliases `GameId = String` but the api
    * module shouldn't depend on `domain` for this one-line alias).
    */
  type GameId = String

  /** Header name carrying the caller's session id. Generated client-side (UUID,
    * persisted in localStorage on the web-ui, generated at startup on the TUI)
    * and required on every mutating request so the gateway can refuse moves
    * from sessions that aren't an active player on the targeted game.
    */
  val SessionHeader: String = "X-Session-Id"

  private val errorBase =
    endpoint.errorOut(
      statusCode(StatusCode.BadRequest).and(jsonBody[ErrorDto])
    )

  /** Game-scoped base — `/api/games/{id}/…`. Every per-game endpoint inherits
    * this prefix so the path scheme stays uniform.
    */
  private val gameBase =
    errorBase.in("api" / "games" / path[GameId]("id"))

  /** Tapir input fragment for the session header. Mutating endpoints compose
    * this in so the gateway can gate by session-id; read-only endpoints (state,
    * export, SSE) leave it off — spectators are welcome to follow without
    * identifying themselves.
    */
  private val sessionHeader = header[String](SessionHeader)

  // Explicit derived Schemas for the replay DTOs. `ReplayFrame` embeds a
  // `BoardStateDto`; without `ReplayFrame`'s Schema as a given, tapir's
  // `generic.auto` mis-derives the `List[ReplayFrame]` in `ReplayResponse` as a
  // sum type. Deriving them here (BoardStateDto's own Schema still comes from
  // `generic.auto`) makes the list resolve via its element schema.
  private given Schema[ReplayFrame]    = Schema.derived
  private given Schema[ReplayResponse] = Schema.derived

  // Analysis DTOs: derive explicitly so the nested `List[MoveAnalysisDto]` in
  // `GameAnalysisDto` resolves via its element schema (same reason as replay).
  private given Schema[OpeningDto]        = Schema.derived
  private given Schema[MoveAnalysisDto]   = Schema.derived
  private given Schema[GameAnalysisDto]   = Schema.derived
  private given Schema[AnalyzeRequestDto] = Schema.derived

  /** POST /api/analyze — rate a game given as PGN (per-move quality, named
    * opening, per-side accuracy). Read-only; works for any finished game,
    * including a pasted one. Reuses the engine in game-service.
    */
  val postAnalyze
      : PublicEndpoint[AnalyzeRequestDto, ErrorDto, GameAnalysisDto, Any] =
    errorBase.post
      .in("api" / "analyze")
      .in(jsonBody[AnalyzeRequestDto])
      .out(jsonBody[GameAnalysisDto])
      .name("postAnalyze")
      .description(
        "Analyze a game given as PGN: per-move ratings (NAG glyphs), the named " +
          "opening, and per-side accuracy."
      )

  /** POST /api/games — create a new game. With an empty body (or `load: null`)
    * the gateway starts from the standard initial position. With `load` set the
    * gateway tries to import the payload as FEN, PGN, or JSON (auto-detected)
    * and starts from that position.
    *
    * Returns the new id alongside the initial state so the client can address
    * subsequent calls (`/api/games/{id}/...`) without an extra round-trip.
    */
  val postCreateGame: PublicEndpoint[
    (String, CreateGameRequest),
    ErrorDto,
    GameSnapshot,
    Any
  ] =
    errorBase.post
      .in("api" / "games")
      .in(sessionHeader)
      .in(jsonBody[CreateGameRequest])
      .out(jsonBody[GameSnapshot])
      .name("postCreateGame")
      .description(
        "Create a new game. Empty body starts from the initial position; " +
          "set `load` to import a FEN / PGN / JSON payload."
      )

  /** GET /api/games/{id}/state — full snapshot of the given board.
    *
    * The optional `format` query parameter selects the response shape:
    *   - absent or `view` → [[BoardStateDto]] (UI projection, default)
    *   - `fen` / `pgn` / `json` → [[ExportResponse]] (canonical export)
    */
  val getState
      : PublicEndpoint[(GameId, Option[String]), ErrorDto, StateResponse, Any] =
    gameBase.get
      .in("state")
      .in(
        query[Option[String]]("format")
          .description(
            "Optional response format: view (default), fen, pgn, or json"
          )
      )
      .out(
        oneOf[StateResponse](
          oneOfVariant[StateResponse.View](
            jsonBody[BoardStateDto].mapTo[StateResponse.View]
          ),
          oneOfVariant[StateResponse.Export](
            jsonBody[ExportResponse].mapTo[StateResponse.Export]
          )
        )
      )
      .name("getState")
      .description(
        "Current board state. Without ?format= returns the UI projection; "
          + "with ?format=fen|pgn|json returns the canonical export."
      )

  /** POST /api/games/{id}/move — apply a move to the given game. */
  val postMove: PublicEndpoint[
    (GameId, String, MoveRequest),
    ErrorDto,
    BoardStateDto,
    Any
  ] =
    gameBase.post
      .in("move")
      .in(sessionHeader)
      .in(jsonBody[MoveRequest])
      .out(jsonBody[BoardStateDto])
      .name("postMove")
      .description("Apply a move (coordinate or SAN notation)")

  /** POST /api/games/{id}/undo — revert the last half-move. */
  val postUndo: PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
    gameBase.post
      .in("undo")
      .in(sessionHeader)
      .out(jsonBody[BoardStateDto])
      .name("postUndo")

  /** POST /api/games/{id}/redo — reapply an undone half-move. */
  val postRedo: PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
    gameBase.post
      .in("redo")
      .in(sessionHeader)
      .out(jsonBody[BoardStateDto])
      .name("postRedo")

  /** POST /api/games/{id}/draw — claim a draw. */
  val postDraw: PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
    gameBase.post
      .in("draw")
      .in(sessionHeader)
      .out(jsonBody[BoardStateDto])
      .name("postDraw")

  /** POST /api/games/{id}/forfeit — the side to move resigns. */
  val postForfeit
      : PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
    gameBase.post
      .in("forfeit")
      .in(sessionHeader)
      .out(jsonBody[BoardStateDto])
      .name("postForfeit")
      .description(
        "The side to move forfeits; the opponent is recorded as the winner."
      )

  /** GET /api/games/{id}/export/{format} — serialise the current position.
    *
    * Kept as an alias for back-compat; new clients should prefer `GET
    * /api/games/{id}/state?format=…`.
    */
  val getExport
      : PublicEndpoint[(GameId, String), ErrorDto, ExportResponse, Any] =
    gameBase.get
      .in("export" / path[String]("format"))
      .out(jsonBody[ExportResponse])
      .name("getExport")
      .description(
        "Serialize the current game as fen, pgn, or json. "
          + "Prefer GET /api/games/{id}/state?format=… for new clients."
      )

  /** GET /api/stack-info — read-only "which backend is this gateway configured
    * for" probe. Surfaced as a chip on the `/dev` index in the web-ui and
    * inside the TUI prompt during perf testing. Public, not dev-gated — knowing
    * the backend is useful in any deployment.
    */
  val getStackInfo: PublicEndpoint[Unit, ErrorDto, StackInfoResponse, Any] =
    errorBase.get
      .in("api" / "stack-info")
      .out(jsonBody[StackInfoResponse])
      .name("getStackInfo")
      .description(
        "Identify the persistence backend + active projection extras."
      )

  /** GET /api/games/{id}/legal-moves?from=<sq> — destinations the piece at
    * `from` can legally move to. Powers the web-ui's move-preview overlay and
    * the TUI's `preview <sq>` command. Server-side cached per `(gameId,
    * position)` and invalidated whenever the game state changes.
    */
  val getLegalMoves: PublicEndpoint[
    (GameId, String),
    ErrorDto,
    LegalMovesResponse,
    Any
  ] =
    gameBase.get
      .in("legal-moves")
      .in(query[String]("from").description("Source square, e.g. e2"))
      .out(jsonBody[LegalMovesResponse])
      .name("getLegalMoves")
      .description(
        "Squares the piece on `from` can legally move to (king-safety filtered)."
      )

  /** GET /api/games/{id}/threats — squares of own pieces (active color)
    * currently under attack. Powers the web-ui's threat-detection overlay.
    */
  val getThreats: PublicEndpoint[GameId, ErrorDto, ThreatsResponse, Any] =
    gameBase.get
      .in("threats")
      .out(jsonBody[ThreatsResponse])
      .name("getThreats")
      .description("Own pieces (active color) under attack.")

  /** GET /api/games/{id}/attackers?of=<sq> — squares of pieces attacking `of`.
    * Caller decides which color: typically the UI passes a square that's
    * currently threatened and gets back the opposing pieces threatening it.
    */
  val getAttackers: PublicEndpoint[
    (GameId, String),
    ErrorDto,
    AttackersResponse,
    Any
  ] =
    gameBase.get
      .in("attackers")
      .in(query[String]("of").description("Target square, e.g. e4"))
      .out(jsonBody[AttackersResponse])
      .name("getAttackers")
      .description(
        "Squares of opposing pieces attacking the given square."
      )

  /** GET /api/games/{id}/replay — every position of the game, oldest first
    * (index 0 = initial position, k = the position after the k-th half-move),
    * for the web-ui's move-by-move replay of a finished game. Read-only (no
    * session header) like `getState`/`getExport` — spectators replay too.
    */
  val getReplay: PublicEndpoint[GameId, ErrorDto, ReplayResponse, Any] =
    gameBase.get
      .in("replay")
      .out(jsonBody[ReplayResponse])
      .name("getReplay")
      .description(
        "Every position of the game, oldest first (0 = initial, k = after " +
          "move k), for client-side replay of a finished game."
      )

  /** All public endpoints — useful for generating OpenAPI docs. The internal
    * coordination endpoint (`postRegisterPlayers`) is not in this list so it
    * doesn't show up in Swagger.
    */
  val all: List[AnyEndpoint] = List(
    postCreateGame,
    getState,
    postMove,
    postUndo,
    postRedo,
    postDraw,
    postForfeit,
    getExport,
    getReplay,
    getLegalMoves,
    getThreats,
    getAttackers,
    getStackInfo,
    postAnalyze
  )

  /** POST /internal/games/{id}/players — lobby-service hand-off.
    *
    * Called by the lobby-service when a lobby transitions Waiting→Started so
    * the gateway's `SessionRegistry` swaps the local-only entry (host was
    * registered alone via `POST /api/games`) for the lobby's actual host+guest
    * pair. Returns 200 with no body on success.
    *
    * Excluded from `all` so Swagger doesn't expose it. A production deployment
    * should additionally gate this route with a shared secret; for the dev demo
    * we rely on the docker-compose network not being publicly reachable.
    */
  val postRegisterPlayers
      : PublicEndpoint[(GameId, RegisterPlayersRequest), ErrorDto, Unit, Any] =
    errorBase.post
      .in("internal" / "games" / path[GameId]("id") / "players")
      .in(jsonBody[RegisterPlayersRequest])
      .name("postRegisterPlayers")
      .description(
        "Internal: lobby-service informs the gateway of a lobby's host + " +
          "guest sessions when its game starts."
      )

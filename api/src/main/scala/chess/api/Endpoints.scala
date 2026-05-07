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
  * **Routing**: every game endpoint is scoped under `/api/games/{id}/...`
  * so multiple games can run side-by-side. The single un-scoped exception is
  * `POST /api/games`, which mints a new game (with optional `load` body) and
  * returns its id. The previous `POST /api/new` and `POST /api/load`
  * collapsed into that one shape.
  */
object Endpoints:

  /** Tapir alias for the path's gameId segment — keeps the type signatures
    * legible (the codec module already aliases `GameId = String` but the
    * api module shouldn't depend on `domain` for this one-line alias).
    */
  type GameId = String

  /** Header name carrying the caller's session id. Generated client-side
    * (UUID, persisted in localStorage on the web-ui, generated at startup
    * on the TUI) and required on every mutating request so the gateway
    * can refuse moves from sessions that aren't an active player on the
    * targeted game.
    */
  val SessionHeader: String = "X-Session-Id"

  private val errorBase =
    endpoint.errorOut(
      statusCode(StatusCode.BadRequest).and(jsonBody[ErrorDto])
    )

  /** Game-scoped base — `/api/games/{id}/…`. Every per-game endpoint
    * inherits this prefix so the path scheme stays uniform.
    */
  private val gameBase =
    errorBase.in("api" / "games" / path[GameId]("id"))

  /** Tapir input fragment for the session header. Mutating endpoints
    * compose this in so the gateway can gate by session-id; read-only
    * endpoints (state, export, SSE) leave it off — spectators are
    * welcome to follow without identifying themselves.
    */
  private val sessionHeader = header[String](SessionHeader)

  /** POST /api/games — create a new game. With an empty body (or `load:
    * null`) the gateway starts from the standard initial position. With
    * `load` set the gateway tries to import the payload as FEN, PGN, or
    * JSON (auto-detected) and starts from that position.
    *
    * Returns the new id alongside the initial state so the client can
    * address subsequent calls (`/api/games/{id}/...`) without an extra
    * round-trip.
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
  val postUndo
      : PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
    gameBase.post
      .in("undo")
      .in(sessionHeader)
      .out(jsonBody[BoardStateDto])
      .name("postUndo")

  /** POST /api/games/{id}/redo — reapply an undone half-move. */
  val postRedo
      : PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
    gameBase.post
      .in("redo")
      .in(sessionHeader)
      .out(jsonBody[BoardStateDto])
      .name("postRedo")

  /** POST /api/games/{id}/draw — claim a draw. */
  val postDraw
      : PublicEndpoint[(GameId, String), ErrorDto, BoardStateDto, Any] =
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

  /** GET /api/games/{id}/export/{format} — serialise the current
    * position.
    *
    * Kept as an alias for back-compat; new clients should prefer
    * `GET /api/games/{id}/state?format=…`.
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

  /** All endpoints — useful for generating OpenAPI docs. */
  val all: List[AnyEndpoint] = List(
    postCreateGame,
    getState,
    postMove,
    postUndo,
    postRedo,
    postDraw,
    postForfeit,
    getExport
  )

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
  */
object Endpoints:

  private val apiBase =
    endpoint
      .in("api")
      .errorOut(
        statusCode(StatusCode.BadRequest).and(jsonBody[ErrorDto])
      )

  /** GET /api/state — full snapshot of the current board.
    *
    * The optional `format` query parameter selects the response shape:
    *   - absent or `view` → [[BoardStateDto]] (UI projection, default)
    *   - `fen` / `pgn` / `json` → [[ExportResponse]] (canonical export)
    */
  val getState: PublicEndpoint[Option[String], ErrorDto, StateResponse, Any] =
    apiBase.get
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

  /** POST /api/move — apply a move. */
  val postMove: PublicEndpoint[MoveRequest, ErrorDto, BoardStateDto, Any] =
    apiBase.post
      .in("move")
      .in(jsonBody[MoveRequest])
      .out(jsonBody[BoardStateDto])
      .name("postMove")
      .description("Apply a move (coordinate or SAN notation)")

  /** POST /api/undo — revert the last half-move. */
  val postUndo: PublicEndpoint[Unit, ErrorDto, BoardStateDto, Any] =
    apiBase.post
      .in("undo")
      .out(jsonBody[BoardStateDto])
      .name("postUndo")

  /** POST /api/redo — reapply an undone half-move. */
  val postRedo: PublicEndpoint[Unit, ErrorDto, BoardStateDto, Any] =
    apiBase.post
      .in("redo")
      .out(jsonBody[BoardStateDto])
      .name("postRedo")

  /** POST /api/draw — claim a draw (50-move / threefold repetition). */
  val postDraw: PublicEndpoint[Unit, ErrorDto, BoardStateDto, Any] =
    apiBase.post
      .in("draw")
      .out(jsonBody[BoardStateDto])
      .name("postDraw")

  /** POST /api/new — reset the game to the starting position. */
  val postNew: PublicEndpoint[Unit, ErrorDto, BoardStateDto, Any] =
    apiBase.post
      .in("new")
      .out(jsonBody[BoardStateDto])
      .name("postNew")

  /** POST /api/quit — signal shutdown. Returns a literal `{"quit":true}`
    * envelope; we keep it as-is for backward compatibility with the old UI.
    */
  final case class QuitAck(quit: Boolean)
  object QuitAck:
    given zio.json.JsonEncoder[QuitAck] =
      zio.json.DeriveJsonEncoder.gen[QuitAck]
    given zio.json.JsonDecoder[QuitAck] =
      zio.json.DeriveJsonDecoder.gen[QuitAck]

  val postQuit: PublicEndpoint[Unit, ErrorDto, QuitAck, Any] =
    apiBase.post
      .in("quit")
      .out(jsonBody[QuitAck])
      .name("postQuit")

  /** POST /api/load — import a game from FEN, PGN, or JSON (auto-detected). */
  val postLoad: PublicEndpoint[LoadRequest, ErrorDto, BoardStateDto, Any] =
    apiBase.post
      .in("load")
      .in(jsonBody[LoadRequest])
      .out(jsonBody[BoardStateDto])
      .name("postLoad")
      .description("Load a game from a FEN, PGN, or JSON payload")

  /** GET /api/export/{format} — serialize the current position.
    *
    * Kept as an alias for back-compat; new clients should prefer `GET
    * /api/state?format=…` so format selection lives in one place.
    */
  val getExport: PublicEndpoint[String, ErrorDto, ExportResponse, Any] =
    apiBase.get
      .in("export" / path[String]("format"))
      .out(jsonBody[ExportResponse])
      .name("getExport")
      .description(
        "Serialize the current game as fen, pgn, or json. "
          + "Prefer GET /api/state?format=… for new clients."
      )

  /** All endpoints — useful for generating OpenAPI docs. */
  val all: List[AnyEndpoint] = List(
    getState,
    postMove,
    postUndo,
    postRedo,
    postDraw,
    postNew,
    postQuit,
    postLoad,
    getExport
  )

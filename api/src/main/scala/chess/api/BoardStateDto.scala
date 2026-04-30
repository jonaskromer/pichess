package chess.api

import zio.json.*

/** Wire DTOs for the pichess HTTP API.
  *
  * Shared between the gateway (JVM, serializes) and the Laminar web-ui (JS,
  * deserializes). Keeping them in a single cross-compiled module is what makes
  * the type guarantee real — adding a field on either side is a compile error
  * until the other side catches up.
  */
final case class SquareDto(
    pos: String,
    squareColor: String,
    @jsonExplicitNull piece: Option[String],
    @jsonExplicitNull pieceColor: Option[String]
)

object SquareDto:
  given JsonEncoder[SquareDto] = DeriveJsonEncoder.gen[SquareDto]
  given JsonDecoder[SquareDto] = DeriveJsonDecoder.gen[SquareDto]

final case class MoveEntryDto(color: String, san: String)

object MoveEntryDto:
  given JsonEncoder[MoveEntryDto] = DeriveJsonEncoder.gen[MoveEntryDto]
  given JsonDecoder[MoveEntryDto] = DeriveJsonDecoder.gen[MoveEntryDto]

/** Wire-format game outcome.
  *
  * kind == "playing" → `winner` and `reason` are null kind == "checkmate" →
  * `winner` is "white" or "black"; `reason` is null kind == "draw" → `reason`
  * names the draw cause; `winner` is null kind == "resignation" → `winner` is
  * the side that did NOT resign; `reason` is null
  *
  * Lowercase / camelCase strings match the rest of `BoardStateDto`
  * (`activeColor`, square colors, piece colors). The canonical (Title-cased)
  * status enum is reachable via `GET /api/state?format=json`.
  */
final case class GameStatusDto(
    kind: String,
    @jsonExplicitNull winner: Option[String],
    @jsonExplicitNull reason: Option[String]
)

object GameStatusDto:
  val Playing: GameStatusDto = GameStatusDto("playing", None, None)
  def checkmate(winner: String): GameStatusDto =
    GameStatusDto("checkmate", Some(winner), None)
  def draw(reason: String): GameStatusDto =
    GameStatusDto("draw", None, Some(reason))
  def resignation(winner: String): GameStatusDto =
    GameStatusDto("resignation", Some(winner), None)

  given JsonEncoder[GameStatusDto] = DeriveJsonEncoder.gen[GameStatusDto]
  given JsonDecoder[GameStatusDto] = DeriveJsonDecoder.gen[GameStatusDto]

final case class BoardStateDto(
    squares: List[SquareDto],
    activeColor: String,
    moveLog: List[MoveEntryDto],
    @jsonExplicitNull error: Option[String],
    inCheck: Boolean,
    @jsonExplicitNull checkedKingPos: Option[String],
    status: GameStatusDto
)

object BoardStateDto:
  given JsonEncoder[BoardStateDto] = DeriveJsonEncoder.gen[BoardStateDto]
  given JsonDecoder[BoardStateDto] = DeriveJsonDecoder.gen[BoardStateDto]

final case class MoveRequest(move: String)

object MoveRequest:
  given JsonEncoder[MoveRequest] = DeriveJsonEncoder.gen[MoveRequest]
  given JsonDecoder[MoveRequest] = DeriveJsonDecoder.gen[MoveRequest]

final case class ErrorDto(error: String)

object ErrorDto:
  given JsonEncoder[ErrorDto] = DeriveJsonEncoder.gen[ErrorDto]
  given JsonDecoder[ErrorDto] = DeriveJsonDecoder.gen[ErrorDto]

final case class LoadRequest(raw: String)

object LoadRequest:
  given JsonEncoder[LoadRequest] = DeriveJsonEncoder.gen[LoadRequest]
  given JsonDecoder[LoadRequest] = DeriveJsonDecoder.gen[LoadRequest]

final case class ExportResponse(format: String, content: String)

object ExportResponse:
  given JsonEncoder[ExportResponse] = DeriveJsonEncoder.gen[ExportResponse]
  given JsonDecoder[ExportResponse] = DeriveJsonDecoder.gen[ExportResponse]

/** Discriminated response for `GET /api/state`.
  *
  * The endpoint takes an optional `?format=` query parameter:
  *   - absent or `view` → [[StateResponse.View]] holding [[BoardStateDto]]
  *   - `fen` / `pgn` / `json` → [[StateResponse.Export]] holding
  *     [[ExportResponse]]
  *
  * Tapir's `oneOf` discriminates by the runtime variant on the server side. On
  * the client side, the variants' JSON shapes are disjoint (no shared field
  * names), so zio-json's strict decoders pick the right one without an explicit
  * type tag on the wire.
  */
sealed trait StateResponse

object StateResponse:
  final case class View(value: BoardStateDto) extends StateResponse
  final case class Export(value: ExportResponse) extends StateResponse

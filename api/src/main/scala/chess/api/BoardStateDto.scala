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
    @jsonExplicitNull pieceColor: Option[String],
)

object SquareDto:
  given JsonEncoder[SquareDto] = DeriveJsonEncoder.gen[SquareDto]
  given JsonDecoder[SquareDto] = DeriveJsonDecoder.gen[SquareDto]

final case class MoveEntryDto(color: String, san: String)

object MoveEntryDto:
  given JsonEncoder[MoveEntryDto] = DeriveJsonEncoder.gen[MoveEntryDto]
  given JsonDecoder[MoveEntryDto] = DeriveJsonDecoder.gen[MoveEntryDto]

final case class BoardStateDto(
    squares: List[SquareDto],
    activeColor: String,
    moveLog: List[MoveEntryDto],
    @jsonExplicitNull error: Option[String],
    inCheck: Boolean,
    @jsonExplicitNull checkedKingPos: Option[String],
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

package chess.tui

import chess.api.{
  BoardStateDto,
  Endpoints,
  ErrorDto,
  ExportResponse,
  LoadRequest,
  MoveRequest,
  StateResponse
}
import sttp.client3.{SttpBackend, UriContext}
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.*

/** Typed HTTP client over the gateway's REST surface. Routes every call
  * through the same `chess.api.Endpoints` definitions the gateway serves
  * and the web-ui consumes — the contract is shared, so a renamed endpoint
  * is a compile error here.
  *
  * Each method returns `Either[ErrorDto, A]`. Decode failures (HTTP-level
  * surprises that aren't the documented error envelope) crash the effect;
  * documented `400 ErrorDto` responses come back as `Left`.
  */
final class TuiClient(
    baseUri: Uri,
    backend: SttpBackend[Task, Any]
):

  private val sttpInterpreter = SttpClientInterpreter()

  private def call[I, O](
      endpoint: sttp.tapir.PublicEndpoint[I, ErrorDto, O, Any],
      input: I
  ): Task[Either[ErrorDto, O]] =
    val request =
      sttpInterpreter
        .toRequestThrowDecodeFailures(endpoint, Some(baseUri))
        .apply(input)
    backend.send(request).map(_.body)

  def newGame: Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postNew, ())

  def state: Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.getState, None).map {
      case Right(StateResponse.View(dto))    => Right(dto)
      case Right(StateResponse.Export(_))    =>
        // We only ever pass `None` for format above, so the gateway's oneOf
        // resolution returns the View variant. The Export branch is
        // unreachable for this caller; surface it as an unexpected shape
        // rather than swallowing it silently.
        Left(ErrorDto("Unexpected export response from /api/state"))
      case Left(err) => Left(err)
    }

  def move(raw: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postMove, MoveRequest(raw))

  def undo: Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postUndo, ())

  def redo: Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postRedo, ())

  def claimDraw: Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postDraw, ())

  def forfeit: Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postForfeit, ())

  def load(raw: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postLoad, LoadRequest(raw))

  def exportAs(format: String): Task[Either[ErrorDto, ExportResponse]] =
    call(Endpoints.getExport, format)

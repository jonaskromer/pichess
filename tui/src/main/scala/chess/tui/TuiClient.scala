package chess.tui

import chess.api.{
  BoardStateDto,
  CreateGameRequest,
  Endpoints,
  ErrorDto,
  ExportResponse,
  GameSnapshot,
  MoveRequest,
  StateResponse
}
import sttp.client3.{SttpBackend, UriContext}
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.*

/** Typed HTTP client over the gateway's REST surface. Routes every call
  * through the same `chess.api.Endpoints` definitions the gateway serves
  * and the web-ui consumes — the contract is shared, so a renamed
  * endpoint is a compile error here.
  *
  * Every gameplay endpoint takes the gameId as the first argument so a
  * single client instance handles multiple games (Phase 2 / lobby flows).
  *
  * Each method returns `Either[ErrorDto, A]`. Decode failures (HTTP-level
  * surprises that aren't the documented error envelope) crash the effect;
  * documented `400 ErrorDto` responses come back as `Left`.
  */
final class TuiClient(
    baseUri: Uri,
    backend: SttpBackend[Task, Any],
    sessionId: String
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

  /** Create a fresh game (or load one). Returns the new id alongside the
    * initial state so callers can address subsequent calls without an
    * extra round-trip.
    */
  def createGame(load: Option[String] = None): Task[Either[ErrorDto, GameSnapshot]] =
    call(Endpoints.postCreateGame, (sessionId, CreateGameRequest(load)))

  def state(gameId: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.getState, (gameId, None)).map {
      case Right(StateResponse.View(dto))    => Right(dto)
      case Right(StateResponse.Export(_))    =>
        // We only ever pass `None` for format above, so the gateway's oneOf
        // resolution returns the View variant. The Export branch is
        // unreachable for this caller; surface it as an unexpected shape
        // rather than swallowing it silently.
        Left(ErrorDto("Unexpected export response from /api/state"))
      case Left(err) => Left(err)
    }

  def move(gameId: String, raw: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postMove, (gameId, sessionId, MoveRequest(raw)))

  def undo(gameId: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postUndo, (gameId, sessionId))

  def redo(gameId: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postRedo, (gameId, sessionId))

  def claimDraw(gameId: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postDraw, (gameId, sessionId))

  def forfeit(gameId: String): Task[Either[ErrorDto, BoardStateDto]] =
    call(Endpoints.postForfeit, (gameId, sessionId))

  def exportAs(gameId: String, format: String): Task[Either[ErrorDto, ExportResponse]] =
    call(Endpoints.getExport, (gameId, format))

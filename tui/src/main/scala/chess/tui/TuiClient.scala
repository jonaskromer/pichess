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
import sttp.client3.{SttpBackend, UriContext, basicRequest}
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.*
import zio.json.*

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

  // --------------------------------------------------------------------------
  // Lobby calls (Phase 2). Routed through the gateway's `/lobbies/*` reverse
  // proxy so the TUI hits a single origin — same wire format as the web-ui.
  // We don't depend on the lobby-service module from tui, so the JSON shapes
  // are mirrored as small case classes below.
  // --------------------------------------------------------------------------

  /** Create a new lobby. The host's session id is set to this client's,
    * so the gateway will register them as the host player when the lobby
    * starts a game.
    */
  def createLobby(
      nickname: String,
      visibility: TuiClient.Visibility,
      allowUndo: Boolean = true,
      allowSpectate: Boolean = true,
      spectatorLimit: Int = 8
  ): Task[Either[String, TuiClient.LobbyView]] =
    val payload = TuiClient
      .CreateLobbyPayload(
        hostNickname = nickname,
        hostSessionId = sessionId,
        visibility = visibility.toString,
        allowUndo = allowUndo,
        allowSpectate = allowSpectate,
        spectatorLimit = spectatorLimit
      )
      .toJson
    sendJson("POST", lobbiesUri(""), Some(payload)).map(decodeLobby)

  def joinLobbyByCode(
      code: String,
      nickname: String
  ): Task[Either[String, TuiClient.LobbyView]] =
    val payload = TuiClient
      .JoinLobbyPayload(guestNickname = nickname, guestSessionId = sessionId)
      .toJson
    sendJson(
      "POST",
      lobbiesUri(s"by-code/${code.trim.toUpperCase}/join"),
      Some(payload)
    ).map(decodeLobby)

  def findLobbyByCode(code: String): Task[Either[String, TuiClient.LobbyView]] =
    sendJson(
      "GET",
      lobbiesUri(s"by-code/${code.trim.toUpperCase}"),
      None
    ).map(decodeLobby)

  def listPublicLobbies(): Task[Either[String, List[TuiClient.LobbyView]]] =
    sendJson("GET", lobbiesUri("public"), None).map { result =>
      result.flatMap(raw =>
        raw
          .fromJson[TuiClient.PublicLobbiesResponse]
          .map(_.lobbies)
          .left
          .map(err => s"Bad public-lobbies payload: $err")
      )
    }

  def startLobby(
      lobbyId: String,
      gameId: String
  ): Task[Either[String, TuiClient.LobbyView]] =
    val payload = TuiClient.StartGamePayload(gameId).toJson
    sendJson("POST", lobbiesUri(s"$lobbyId/start"), Some(payload))
      .map(decodeLobby)

  // --- internals -----------------------------------------------------------

  private def lobbiesUri(suffix: String): Uri =
    val base = baseUri.addPath("lobbies")
    if suffix.isEmpty then base
    else base.addPath(suffix.split('/').toList.filter(_.nonEmpty))

  /** Bare HTTP wrapper for the lobby calls — no Tapir contract because the
    * gateway proxy is verbatim. Returns the response body or a friendly
    * error string assembled from the upstream status + body.
    */
  private def sendJson(
      method: String,
      uri: Uri,
      body: Option[String]
  ): Task[Either[String, String]] =
    val base = basicRequest
      .method(sttp.model.Method(method), uri)
      .header("Accept", "application/json")
      .header("X-Session-Id", sessionId)
    val request = body match
      case Some(b) =>
        base
          .header("Content-Type", "application/json")
          .body(b)
      case None => base
    backend.send(request).map { resp =>
      if resp.code.isSuccess then Right(resp.body.fold(identity, identity))
      else
        val msg = resp.body.fold(identity, identity)
        Left(s"HTTP ${resp.code.code}: $msg")
    }

  private def decodeLobby(
      raw: Either[String, String]
  ): Either[String, TuiClient.LobbyView] =
    raw.flatMap(body =>
      body
        .fromJson[TuiClient.LobbyView]
        .left
        .map(err => s"Bad lobby payload: $err")
    )

object TuiClient:

  enum Visibility:
    case Public, Private

  /** Wire-shape mirror of `chess.lobby.CreateLobbyRequest` — kept here so
    * the tui module doesn't have to depend on lobby-service.
    */
  final case class CreateLobbyPayload(
      hostNickname: String,
      hostSessionId: String,
      visibility: String,
      allowUndo: Boolean,
      allowSpectate: Boolean,
      spectatorLimit: Int
  )
  object CreateLobbyPayload:
    given JsonCodec[CreateLobbyPayload] = DeriveJsonCodec.gen

  final case class JoinLobbyPayload(
      guestNickname: String,
      guestSessionId: String
  )
  object JoinLobbyPayload:
    given JsonCodec[JoinLobbyPayload] = DeriveJsonCodec.gen

  final case class StartGamePayload(gameId: String)
  object StartGamePayload:
    given JsonCodec[StartGamePayload] = DeriveJsonCodec.gen

  /** Wire-shape mirror of `chess.model.Lobby` — only the fields we render. */
  final case class LobbyView(
      id: String,
      inviteCode: String,
      hostNickname: String,
      hostSessionId: String,
      guestNickname: Option[String],
      guestSessionId: Option[String],
      visibility: String,
      allowUndo: Boolean,
      allowSpectate: Boolean,
      spectatorLimit: Int,
      status: String,
      gameId: Option[String]
  )
  object LobbyView:
    given JsonCodec[LobbyView] = DeriveJsonCodec.gen

  final case class PublicLobbiesResponse(lobbies: List[LobbyView])
  object PublicLobbiesResponse:
    given JsonCodec[PublicLobbiesResponse] = DeriveJsonCodec.gen

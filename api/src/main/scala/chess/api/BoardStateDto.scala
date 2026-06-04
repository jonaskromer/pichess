package chess.api

import java.nio.ByteBuffer

import boopickle.Default.*
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
  given Pickler[SquareDto]     = generatePickler

final case class MoveEntryDto(color: String, san: String)

object MoveEntryDto:
  given JsonEncoder[MoveEntryDto] = DeriveJsonEncoder.gen[MoveEntryDto]
  given JsonDecoder[MoveEntryDto] = DeriveJsonDecoder.gen[MoveEntryDto]
  given Pickler[MoveEntryDto]     = generatePickler

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
  given Pickler[GameStatusDto]     = generatePickler

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
  given Pickler[BoardStateDto]     = generatePickler

  /** Encode a [[BoardStateDto]] to the bytes carried as the `board_state`
    * payload of gRPC `StateReply`. Picked from a JMH shoot-out
    * (`BoardStateDtoBenchmark`): boopickle matches the hand-tuned FEN
    * round-trip (~12 µs both ways) on a binary wire format, vs
    * zio-schema-protobuf which was 33× slower for this DTO shape.
    */
  def encodeBytes(dto: BoardStateDto): Array[Byte] =
    val buf = Pickle.intoBytes(dto)
    val arr = new Array[Byte](buf.remaining)
    buf.get(arr)
    arr

  /** Decode the bytes back into a [[BoardStateDto]]. Throws if the
    * bytes are corrupted — gateway's `replyToDto` catches via
    * `ZIO.attempt`. */
  def decodeBytes(bytes: Array[Byte]): BoardStateDto =
    Unpickle[BoardStateDto].fromBytes(ByteBuffer.wrap(bytes))

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

/** Body for `POST /api/games`. With `load = None` the gateway creates a
  * fresh game from the standard initial position. With `load = Some(raw)`
  * it imports a serialised game (FEN, PGN, or JSON, auto-detected). The
  * old `POST /api/new` and `POST /api/load` collapsed into this one
  * endpoint when routing went game-scoped.
  *
  * The optional `vsBot` field opts into vs-bot mode. When present, the
  * server creates the game with the supplied bot config: which side
  * the bot plays, its difficulty level, and whether the player has
  * undo/redo enabled in the UI. When absent the game is a regular PvP
  * game (existing behaviour).
  */
final case class CreateGameRequest(
    load: Option[String] = None,
    vsBot: Option[VsBotSettings] = None,
)

object CreateGameRequest:
  given JsonEncoder[CreateGameRequest] = DeriveJsonEncoder.gen[CreateGameRequest]
  given JsonDecoder[CreateGameRequest] = DeriveJsonDecoder.gen[CreateGameRequest]

/** Settings the client supplies when starting a vs-bot game.
  *
  *   - `botSide`:    "white" or "black"
  *   - `difficulty`: one of "Beginner" | "Easy" | "Medium" | "Hard" | "Expert"
  *   - `allowUndo`:  whether the client should expose undo/redo controls
  *
  * Mirrors `chess.bot.engine.BotConfig` exactly (same field names),
  * but lives here as a JSON-DTO so the api module (web-ui shared)
  * doesn't have to pull in the engine package.
  */
final case class VsBotSettings(
    botSide: String,
    difficulty: String,
    allowUndo: Boolean,
)

object VsBotSettings:
  given JsonEncoder[VsBotSettings] = DeriveJsonEncoder.gen[VsBotSettings]
  given JsonDecoder[VsBotSettings] = DeriveJsonDecoder.gen[VsBotSettings]

/** Response for `POST /api/games`. Carries the new game's id alongside its
  * initial state so the client can address subsequent calls without an
  * extra round-trip.
  */
final case class GameSnapshot(id: String, state: BoardStateDto)

object GameSnapshot:
  given JsonEncoder[GameSnapshot] = DeriveJsonEncoder.gen[GameSnapshot]
  given JsonDecoder[GameSnapshot] = DeriveJsonDecoder.gen[GameSnapshot]

/** Body for `POST /internal/games/{id}/players`. Lobby-service calls this
  * when a hosted lobby starts a game so the gateway swaps the local-only
  * session registration for the lobby's actual host+guest pair.
  *
  * Internal coordination only — the endpoint is intentionally absent from
  * the public Swagger surface (Endpoints.all). Production deployments
  * should add a shared-secret check before exposing this route on a
  * public-facing gateway port.
  */
final case class RegisterPlayersRequest(
    hostSessionId: String,
    guestSessionId: Option[String]
)

object RegisterPlayersRequest:
  given JsonEncoder[RegisterPlayersRequest] =
    DeriveJsonEncoder.gen[RegisterPlayersRequest]
  given JsonDecoder[RegisterPlayersRequest] =
    DeriveJsonDecoder.gen[RegisterPlayersRequest]

/** Response for `GET /api/stack-info` — read-only identification of
  * which persistence stack the running gateway has been configured
  * with (driven by the `PICHESS_BACKEND` / `PICHESS_EXTRAS` env vars
  * the Makefile's `stack-*` targets set). The UI's `/dev` index
  * surfaces this as a chip so an operator can tell at a glance which
  * backend is being exercised during a perf-test run.
  */
final case class StackInfoResponse(backend: String, extras: List[String])

object StackInfoResponse:
  given JsonEncoder[StackInfoResponse] =
    DeriveJsonEncoder.gen[StackInfoResponse]
  given JsonDecoder[StackInfoResponse] =
    DeriveJsonDecoder.gen[StackInfoResponse]

/** Phase 4 server-side annotation bundle. Carries the full
  * legal-moves / threats / attackers triple the gateway used to compute
  * locally on cache miss. Game-service builds this from the in-memory
  * `GameState` after every state change and ships it on the wire so the
  * gateway just shuttles it to its annotation cache — no FEN parse, no
  * per-piece `legalMovesFrom` loop.
  *
  * Keys are canonical square labels ("e4", "g1", …) matching
  * [[LegalMovesResponse.from]] / [[ThreatsResponse.threatened]] /
  * [[AttackersResponse.of]] so the gateway can hand each bundle field
  * straight through to its corresponding HTTP endpoint without
  * remapping.
  */
final case class AnnotationsDto(
    legalMovesFrom: Map[String, List[String]],
    threats: List[String],
    attackersOf: Map[String, List[String]]
)

object AnnotationsDto:
  given JsonEncoder[AnnotationsDto] = DeriveJsonEncoder.gen[AnnotationsDto]
  given JsonDecoder[AnnotationsDto] = DeriveJsonDecoder.gen[AnnotationsDto]
  given Pickler[AnnotationsDto]     = generatePickler

  /** Empty bundle — used as the proto3 default when the server didn't
    * attach annotations to the reply (graceful fallback path on the
    * gateway). */
  val Empty: AnnotationsDto = AnnotationsDto(Map.empty, Nil, Map.empty)

  /** Same boopickle round-trip as `BoardStateDto.encodeBytes` —
    * boopickle was the winner of the bench shoot-out for our DTO shapes
    * (zio-schema-protobuf was 33× slower for nested case-class trees). */
  def encodeBytes(dto: AnnotationsDto): Array[Byte] =
    val buf = Pickle.intoBytes(dto)
    val arr = new Array[Byte](buf.remaining)
    buf.get(arr)
    arr

  def decodeBytes(bytes: Array[Byte]): AnnotationsDto =
    Unpickle[AnnotationsDto].fromBytes(ByteBuffer.wrap(bytes))

/** Response for `GET /api/games/{id}/legal-moves?from=<sq>` — the
  * destinations the piece at `from` can legally move to. Pawn promotions
  * collapse to one entry per destination (the promotion choice happens
  * client-side via the existing promotion overlay). Empty list means
  * either the square is empty, holds an opponent piece, or the piece is
  * pinned with no legal move.
  */
final case class LegalMovesResponse(from: String, moves: List[String])

object LegalMovesResponse:
  given JsonEncoder[LegalMovesResponse] =
    DeriveJsonEncoder.gen[LegalMovesResponse]
  given JsonDecoder[LegalMovesResponse] =
    DeriveJsonDecoder.gen[LegalMovesResponse]

/** Response for `GET /api/games/{id}/threats` — squares of own pieces
  * (active color) currently under attack by an opposing piece. Drives
  * the web-ui's "red ring" highlight on threatened pieces.
  */
final case class ThreatsResponse(threatened: List[String])

object ThreatsResponse:
  given JsonEncoder[ThreatsResponse] =
    DeriveJsonEncoder.gen[ThreatsResponse]
  given JsonDecoder[ThreatsResponse] =
    DeriveJsonDecoder.gen[ThreatsResponse]

/** Response for `GET /api/games/{id}/attackers?of=<sq>` — squares of
  * opposing pieces attacking `of`. Empty list when nothing attacks the
  * square.
  */
final case class AttackersResponse(of: String, attackers: List[String])

object AttackersResponse:
  given JsonEncoder[AttackersResponse] =
    DeriveJsonEncoder.gen[AttackersResponse]
  given JsonDecoder[AttackersResponse] =
    DeriveJsonDecoder.gen[AttackersResponse]

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

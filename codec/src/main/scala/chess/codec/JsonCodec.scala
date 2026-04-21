package chess.codec

import chess.model.board.{
  Board,
  CastlingRights,
  DrawReason,
  GameState,
  GameStatus,
  Position
}
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.rules.MoveValidator
import zio.json.*
import zio.json.ast.Json

object JsonCodec:

  // ─── Position helpers (shared between value and field codecs) ──────────────

  private def positionString(p: Position): String = s"${p.col}${p.row}"

  private def parsePosition(s: String): Either[String, Position] =
    if s.length == 2 && s(0) >= 'a' && s(0) <= 'h' && s(1) >= '1' && s(1) <= '8'
    then Right(Position(s(0), s(1).asDigit))
    else Left(s"Invalid position '$s'")

  // ─── Leaf codecs for the small enums (string wire format) ─────────────────
  //
  // Every Scala 3 enum gets a synthesised `values: Array[T]` and a `.toString`
  // returning the case name. That's all we need: encode via `_.toString` and
  // decode by looking the string up in `values`. Adding a new case to any of
  // these enums needs zero codec changes.

  private def enumCodec[T <: reflect.Enum](
      values: Array[T],
      label: String
  ): zio.json.JsonCodec[T] =
    zio.json.JsonCodec(
      JsonEncoder.string.contramap(_.toString),
      JsonDecoder.string.mapOrFail(s =>
        values.find(_.toString == s).toRight(s"Unknown $label '$s'")
      )
    )

  given JsonCodec[Color] = enumCodec(Color.values, "color")
  given JsonCodec[PieceType] = enumCodec(PieceType.values, "piece type")
  given JsonCodec[DrawReason] = enumCodec(DrawReason.values, "draw reason")

  given JsonEncoder[Position] = JsonEncoder.string.contramap(positionString)
  given JsonDecoder[Position] = JsonDecoder.string.mapOrFail(parsePosition)

  // Position-as-Map-key support, so the Board codec falls out of Map[K, V].
  given JsonFieldEncoder[Position] =
    JsonFieldEncoder.string.contramap(positionString)
  given JsonFieldDecoder[Position] =
    JsonFieldDecoder.string.mapOrFail(parsePosition)

  // ─── Auto-derived structural codecs ───────────────────────────────────────
  //
  // Piece, CastlingRights, GameStatus, and the GameState DTO all compose
  // mechanically from the leaf codecs above. zio-json's macro derivation
  // handles them; the wire format is determined entirely by the case class /
  // enum structure plus the leaf codecs in scope.
  //
  // Wire formats produced:
  //   Piece            → {"color":"white","pieceType":"king"}
  //   Board            → {"e1":{"color":"white","pieceType":"king"}, …}
  //   CastlingRights   → {"whiteKingSide":true, …}
  //   GameStatus       → "Playing"
  //                    | {"Checkmate":{"winner":"white"}}
  //                    | {"Draw":{"reason":"stalemate"}}

  given JsonCodec[Piece] = DeriveJsonCodec.gen[Piece]

  given JsonCodec[CastlingRights] = DeriveJsonCodec.gen[CastlingRights]

  given JsonCodec[GameStatus] = DeriveJsonCodec.gen[GameStatus]

  // ─── GameState (derived via DTO) ───────────────────────────────────────────
  //
  // The wire format is a flat object whose fields match GameStateDTO. The
  // domain `GameState` differs from the DTO in two respects:
  //   1. `inCheck` is a *derived* field — recomputed on decode rather than
  //      trusted from the input.
  //   2. `halfmoveClock` and `fullmoveNumber` are optional in the wire format
  //      and default to 0 / 1 respectively.

  private case class GameStateDTO(
      board: Board,
      activeColor: Color,
      castlingRights: CastlingRights,
      enPassantTarget: Option[Position],
      inCheck: Boolean,
      status: GameStatus,
      halfmoveClock: Option[Int] = None,
      fullmoveNumber: Option[Int] = None
  )

  private given JsonCodec[GameStateDTO] = DeriveJsonCodec.gen[GameStateDTO]

  given JsonEncoder[GameState] =
    summon[JsonEncoder[GameStateDTO]].contramap(s =>
      GameStateDTO(
        board = s.board,
        activeColor = s.activeColor,
        castlingRights = s.castlingRights,
        enPassantTarget = s.enPassantTarget,
        inCheck = s.inCheck,
        status = s.status,
        halfmoveClock = Some(s.halfmoveClock),
        fullmoveNumber = Some(s.fullmoveNumber)
      )
    )

  given JsonDecoder[GameState] =
    summon[JsonDecoder[GameStateDTO]].map(dto =>
      GameState(
        board = dto.board,
        activeColor = dto.activeColor,
        enPassantTarget = dto.enPassantTarget,
        inCheck = MoveValidator.isInCheck(dto.board, dto.activeColor),
        castlingRights = dto.castlingRights,
        status = dto.status,
        halfmoveClock = dto.halfmoveClock.getOrElse(0),
        fullmoveNumber = dto.fullmoveNumber.getOrElse(1)
      )
    )

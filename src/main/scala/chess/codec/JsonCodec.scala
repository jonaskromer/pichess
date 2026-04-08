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

  // ─── Helpers ───────────────────────────────────────────────────────────────

  private def colorString(c: Color): String = c match
    case Color.White => "white"
    case Color.Black => "black"

  private def pieceTypeString(pt: PieceType): String = pt match
    case PieceType.King   => "king"
    case PieceType.Queen  => "queen"
    case PieceType.Rook   => "rook"
    case PieceType.Bishop => "bishop"
    case PieceType.Knight => "knight"
    case PieceType.Pawn   => "pawn"

  private def parseColor(s: String): Either[String, Color] = s match
    case "white" => Right(Color.White)
    case "black" => Right(Color.Black)
    case other   => Left(s"Invalid color '$other'")

  private def parsePieceType(s: String): Either[String, PieceType] = s match
    case "king"   => Right(PieceType.King)
    case "queen"  => Right(PieceType.Queen)
    case "rook"   => Right(PieceType.Rook)
    case "bishop" => Right(PieceType.Bishop)
    case "knight" => Right(PieceType.Knight)
    case "pawn"   => Right(PieceType.Pawn)
    case other    => Left(s"Invalid piece type '$other'")

  private def parsePiece(s: String): Either[String, Piece] =
    s.split(' ') match
      case Array(colorStr, typeStr) =>
        for
          color <- parseColor(colorStr)
          pt <- parsePieceType(typeStr)
        yield Piece(color, pt)
      case _ => Left(s"Invalid piece '$s', expected 'color type'")

  private def drawReasonString(r: DrawReason): String = r match
    case DrawReason.Stalemate            => "stalemate"
    case DrawReason.FiftyMoveRule        => "fifty-move rule"
    case DrawReason.InsufficientMaterial => "insufficient material"
    case DrawReason.ThreefoldRepetition  => "threefold repetition"
    case DrawReason.FivefoldRepetition   => "fivefold repetition"

  // ─── Leaf types ────────────────────────────────────────────────────────────

  given JsonEncoder[Color] = JsonEncoder.string.contramap(colorString)

  given JsonDecoder[Color] = JsonDecoder.string.mapOrFail(parseColor)

  given JsonEncoder[PieceType] = JsonEncoder.string.contramap(pieceTypeString)

  given JsonDecoder[PieceType] = JsonDecoder.string.mapOrFail(parsePieceType)

  // ─── Position ──────────────────────────────────────────────────────────────

  given JsonEncoder[Position] =
    JsonEncoder.string.contramap(p => s"${p.col}${p.row}")

  given JsonDecoder[Position] = JsonDecoder.string.mapOrFail: s =>
    if s.length == 2 && s(0) >= 'a' && s(0) <= 'h' && s(1) >= '1' && s(1) <= '8'
    then Right(Position(s(0), s(1).asDigit))
    else Left(s"Invalid position '$s'")

  given JsonFieldEncoder[Position] =
    JsonFieldEncoder.string.contramap(p => s"${p.col}${p.row}")

  given JsonFieldDecoder[Position] =
    JsonFieldDecoder.string.mapOrFail: s =>
      if s.length == 2 && s(0) >= 'a' && s(0) <= 'h' && s(1) >= '1' && s(
          1
        ) <= '8'
      then Right(Position(s(0), s(1).asDigit))
      else Left(s"Invalid position '$s'")

  // ─── Board (explicit codec to ensure Piece is encoded as string) ───────────

  given boardEncoder: JsonEncoder[Board] =
    JsonEncoder[Json].contramap: board =>
      Json.Obj(zio.Chunk.fromIterable(board.map { case (pos, piece) =>
        s"${pos.col}${pos.row}" -> Json.Str(
          s"${colorString(piece.color)} ${pieceTypeString(piece.pieceType)}"
        )
      }))

  private def parsePosition(s: String): Either[String, Position] =
    if s.length == 2 && s(0) >= 'a' && s(0) <= 'h' && s(1) >= '1' && s(1) <= '8'
    then Right(Position(s(0), s(1).asDigit))
    else Left(s"Invalid position '$s'")

  given boardDecoder: JsonDecoder[Board] =
    JsonDecoder[Json].mapOrFail:
      case obj: Json.Obj =>
        obj.fields.foldLeft[Either[String, Board]](Right(Map.empty)) {
          case (acc, (key, value)) =>
            for
              board <- acc
              pos <- parsePosition(key)
              piece <- value match
                case Json.Str(s) => parsePiece(s)
                case other       => Left(s"Expected string for piece at '$key'")
            yield board + (pos -> piece)
        }
      case _ => Left("Expected object for board")

  // ─── Piece (serialized as "white king") ────────────────────────────────────

  given pieceEncoder: JsonEncoder[Piece] = JsonEncoder.string.contramap: p =>
    s"${colorString(p.color)} ${pieceTypeString(p.pieceType)}"

  given pieceDecoder: JsonDecoder[Piece] =
    JsonDecoder.string.mapOrFail(parsePiece)

  // ─── CastlingRights ────────────────────────────────────────────────────────

  given JsonCodec[CastlingRights] = DeriveJsonCodec.gen[CastlingRights]

  // ─── DrawReason ────────────────────────────────────────────────────────────

  given JsonEncoder[DrawReason] = JsonEncoder.string.contramap(drawReasonString)

  given JsonDecoder[DrawReason] = JsonDecoder.string.mapOrFail(parseDrawReason)

  // ─── GameStatus ────────────────────────────────────────────────────────────

  given JsonEncoder[GameStatus] = JsonEncoder[Json].contramap:
    case GameStatus.Playing =>
      Json.Str("playing")
    case GameStatus.Checkmate(color) =>
      Json.Obj("checkmate" -> Json.Str(colorString(color)))
    case GameStatus.Draw(reason) =>
      Json.Obj("draw" -> Json.Str(drawReasonString(reason)))

  private def parseDrawReason(s: String): Either[String, DrawReason] = s match
    case "stalemate"             => Right(DrawReason.Stalemate)
    case "fifty-move rule"       => Right(DrawReason.FiftyMoveRule)
    case "insufficient material" => Right(DrawReason.InsufficientMaterial)
    case "threefold repetition"  => Right(DrawReason.ThreefoldRepetition)
    case "fivefold repetition"   => Right(DrawReason.FivefoldRepetition)
    case other                   => Left(s"Unknown draw reason '$other'")

  given JsonDecoder[GameStatus] = JsonDecoder[Json].mapOrFail:
    case Json.Str("playing") => Right(GameStatus.Playing)
    case obj: Json.Obj =>
      obj.get("checkmate") match
        case Some(Json.Str(c)) =>
          parseColor(c).map(GameStatus.Checkmate(_))
        case _ =>
          obj.get("draw") match
            case Some(Json.Str(r)) =>
              parseDrawReason(r).map(GameStatus.Draw(_))
            case _ => Left("Invalid status object")
    case _ => Left("Invalid status")

  // ─── GameState ─────────────────────────────────────────────────────────────

  given JsonEncoder[GameState] = JsonEncoder[Json].contramap: s =>
    Json.Obj(
      "board" -> boardEncoder.toJsonAST(s.board).toOption.get,
      "activeColor" -> Json.Str(colorString(s.activeColor)),
      "castlingRights" -> summon[JsonEncoder[CastlingRights]]
        .toJsonAST(s.castlingRights)
        .toOption
        .get,
      "enPassantTarget" -> s.enPassantTarget.fold(Json.Null)(p =>
        Json.Str(s"${p.col}${p.row}")
      ),
      "inCheck" -> Json.Bool(s.inCheck),
      "status" -> summon[JsonEncoder[GameStatus]]
        .toJsonAST(s.status)
        .toOption
        .get,
      "halfmoveClock" -> Json.Num(s.halfmoveClock),
      "fullmoveNumber" -> Json.Num(s.fullmoveNumber)
    )

  given JsonDecoder[GameState] = JsonDecoder[Json].mapOrFail:
    case obj: Json.Obj =>
      for
        board <- obj
          .get("board")
          .toRight("Missing 'board'")
          .flatMap(boardDecoder.fromJsonAST(_).left.map(_.toString))
        color <- obj
          .get("activeColor")
          .toRight("Missing 'activeColor'")
          .flatMap:
            case Json.Str(s) => parseColor(s)
            case _           => Left("Expected string for 'activeColor'")
        rights <- obj
          .get("castlingRights")
          .toRight("Missing 'castlingRights'")
          .flatMap(
            summon[JsonDecoder[CastlingRights]]
              .fromJsonAST(_)
              .left
              .map(_.toString)
          )
        ep <- obj.get("enPassantTarget") match
          case None | Some(Json.Null) => Right(None)
          case Some(Json.Str(s)) =>
            if s.length == 2 && s(0) >= 'a' && s(0) <= 'h' && s(1) >= '1' && s(
                1
              ) <= '8'
            then Right(Some(Position(s(0), s(1).asDigit)))
            else Left(s"Invalid en passant target '$s'")
          case _ => Left("Invalid en passant target")
        status <- obj
          .get("status")
          .toRight("Missing 'status'")
          .flatMap(
            summon[JsonDecoder[GameStatus]].fromJsonAST(_).left.map(_.toString)
          )
        hm = obj
          .get("halfmoveClock")
          .flatMap:
            case Json.Num(n) => Some(n.intValue)
            case _           => None
        fm = obj
          .get("fullmoveNumber")
          .flatMap:
            case Json.Num(n) => Some(n.intValue)
            case _           => None
      yield GameState(
        board = board,
        activeColor = color,
        enPassantTarget = ep,
        inCheck = MoveValidator.isInCheck(board, color),
        castlingRights = rights,
        status = status,
        halfmoveClock = hm.getOrElse(0),
        fullmoveNumber = fm.getOrElse(1)
      )
    case _ => Left("Expected JSON object")

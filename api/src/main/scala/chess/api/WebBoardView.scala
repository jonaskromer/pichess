package chess.api

import zio.json.*

import chess.model.board.{DrawReason, GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}

/** Builds the [[BoardStateDto]] consumed by the browser UI (and shipped over
  * the gRPC wire as bytes) from a domain [[GameState]].
  *
  * Lives in `api` (cross-platform) so both producers — the gateway and the
  * game-service — can construct the DTO without each side having its own
  * private converter. The web-ui (Scala.js) just consumes the decoded
  * `BoardStateDto`; it doesn't need this object.
  *
  * The wire field `SquareDto.piece` holds a lowercase piece-type name ("pawn",
  * "knight", …) rather than a Unicode glyph; the Laminar UI uses it as the
  * symbol id when fetching from `/web/pieces/<name>.svg`.
  */
object WebBoardView:

  def toJson(
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String]
  ): String =
    toDto(state, moveLog, error).toJson

  def toDto(
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String]
  ): BoardStateDto =
    val squares = for
      row <- (8 to 1 by -1).toList
      col <- ('a' to 'h').toList
    yield
      val pos = Position(col, row)
      val squareColorName =
        if (col - 'a' + row) % 2 == 1 then "dark" else "light"
      state.board.get(pos) match
        case Some(piece) =>
          SquareDto(
            pos = pos.toString,
            squareColor = squareColorName,
            piece = Some(pieceTypeName(piece.pieceType)),
            pieceColor = Some(colorStr(piece.color))
          )
        case None =>
          SquareDto(
            pos = pos.toString,
            squareColor = squareColorName,
            piece = None,
            pieceColor = None
          )

    val moveLogDtos = moveLog.map { case (color, san) =>
      MoveEntryDto(colorStr(color), san)
    }

    val checkedKingPos =
      if state.inCheck then
        state.board.collectFirst {
          case (pos, Piece(state.activeColor, PieceType.King)) => pos.toString
        }
      else None

    BoardStateDto(
      squares = squares,
      activeColor = colorStr(state.activeColor),
      moveLog = moveLogDtos,
      error = error,
      inCheck = state.inCheck,
      checkedKingPos = checkedKingPos,
      status = statusDto(state.status)
    )

  private def colorStr(color: Color): String = color match
    case Color.White => "white"
    case Color.Black => "black"

  private def pieceTypeName(t: PieceType): String = t match
    case PieceType.Pawn   => "pawn"
    case PieceType.Rook   => "rook"
    case PieceType.Knight => "knight"
    case PieceType.Bishop => "bishop"
    case PieceType.Queen  => "queen"
    case PieceType.King   => "king"

  private def statusDto(status: GameStatus): GameStatusDto = status match
    case GameStatus.Playing => GameStatusDto.Playing
    case GameStatus.Checkmate(winner) =>
      GameStatusDto.checkmate(colorStr(winner))
    case GameStatus.Draw(reason) => GameStatusDto.draw(drawReasonStr(reason))
    case GameStatus.Resignation(winner) =>
      GameStatusDto.resignation(colorStr(winner))
    case GameStatus.Timeout(winner) =>
      GameStatusDto.timeout(colorStr(winner))

  private def drawReasonStr(reason: DrawReason): String = reason match
    case DrawReason.Stalemate            => "stalemate"
    case DrawReason.FiftyMoveRule        => "fiftyMoveRule"
    case DrawReason.InsufficientMaterial => "insufficientMaterial"
    case DrawReason.ThreefoldRepetition  => "threefoldRepetition"
    case DrawReason.FivefoldRepetition   => "fivefoldRepetition"

package chess.view

import chess.api.{BoardStateDto, MoveEntryDto, SquareDto}
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.json.*

/** Builds the [[BoardStateDto]] consumed by the browser UI from a domain
  * [[GameState]]. DTO shape + codecs live in the shared `api` module so the
  * Laminar web-ui decodes the same type the gateway encodes.
  */
object WebBoardView:

  def toJson(
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String],
  ): String =
    toDto(state, moveLog, error).toJson

  def toDto(
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String],
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
            pos         = pos.toString,
            squareColor = squareColorName,
            piece       = Some(PieceUnicode(piece).toString),
            pieceColor  = Some(colorStr(piece.color)),
          )
        case None =>
          SquareDto(
            pos         = pos.toString,
            squareColor = squareColorName,
            piece       = None,
            pieceColor  = None,
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
      squares        = squares,
      activeColor    = colorStr(state.activeColor),
      moveLog        = moveLogDtos,
      error          = error,
      inCheck        = state.inCheck,
      checkedKingPos = checkedKingPos,
    )

  private def colorStr(color: Color): String = color match
    case Color.White => "white"
    case Color.Black => "black"

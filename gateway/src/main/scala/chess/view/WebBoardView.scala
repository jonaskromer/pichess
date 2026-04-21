package chess.view

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.json.*

/** Serializes a [[GameState]] into the JSON payload consumed by the browser
  * UI. Uses `zio-json` derivation so escaping, nulls, and field order are
  * handled by the library rather than by string interpolation.
  */
object WebBoardView:

  // @jsonExplicitNull forces Option.None to render as explicit `null` rather
  // than omitting the field — preserving the JSON shape expected by the
  // browser UI.
  private case class SquareDto(
      pos: String,
      squareColor: String,
      @jsonExplicitNull piece: Option[String],
      @jsonExplicitNull pieceColor: Option[String]
  )

  private case class MoveEntryDto(color: String, san: String)

  private case class WebBoardDto(
      squares: List[SquareDto],
      activeColor: String,
      moveLog: List[MoveEntryDto],
      @jsonExplicitNull error: Option[String],
      inCheck: Boolean,
      @jsonExplicitNull checkedKingPos: Option[String]
  )

  private given JsonEncoder[SquareDto] = DeriveJsonEncoder.gen[SquareDto]
  private given JsonEncoder[MoveEntryDto] =
    DeriveJsonEncoder.gen[MoveEntryDto]
  private given JsonEncoder[WebBoardDto] = DeriveJsonEncoder.gen[WebBoardDto]

  def toJson(
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String]
  ): String =
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
            piece = Some(PieceUnicode(piece).toString),
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
        state.board
          .collectFirst {
            case (pos, Piece(state.activeColor, PieceType.King)) =>
              pos.toString
          }
      else None

    WebBoardDto(
      squares = squares,
      activeColor = colorStr(state.activeColor),
      moveLog = moveLogDtos,
      error = error,
      inCheck = state.inCheck,
      checkedKingPos = checkedKingPos
    ).toJson

  private def colorStr(color: Color): String = color match
    case Color.White => "white"
    case Color.Black => "black"

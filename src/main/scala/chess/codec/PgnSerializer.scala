package chess.codec

import chess.model.board.GameStatus
import chess.model.piece.Color

object PgnSerializer:

  def serialize(
      moveLog: List[(Color, String)],
      status: GameStatus
  ): String =
    val header = List(
      """[Event "πChess Game"]""",
      """[Site "?"]""",
      """[Date "????.??.??"]""",
      """[White "?"]""",
      """[Black "?"]""",
      s"""[Result "${resultString(status)}"]"""
    ).mkString("\n")
    val movetext = formatMovetext(moveLog, status)
    s"$header\n\n$movetext"

  private def resultString(status: GameStatus): String = status match
    case GameStatus.Playing                => "*"
    case GameStatus.Checkmate(Color.White) => "1-0"
    case GameStatus.Checkmate(Color.Black) => "0-1"
    case GameStatus.Draw(_)                => "1/2-1/2"

  private def formatMovetext(
      moveLog: List[(Color, String)],
      status: GameStatus
  ): String =
    val moves = moveLog
      .grouped(2)
      .zipWithIndex
      .map { case (pair, idx) =>
        val number = idx + 1
        (pair: @unchecked) match
          case List((_, white), (_, black)) => s"$number. $white $black"
          case List((_, white))             => s"$number. $white"
      }
      .mkString(" ")
    val result = resultString(status)
    if moves.isEmpty then result else s"$moves $result"

package chess.codec

import chess.model.board.GameStatus
import chess.model.piece.Color

object PgnSerializer:

  def serialize(
      moveLog: List[(Color, String)],
      status: GameStatus
  ): String =
    val result = PgnCodec.encodeResult(status)
    val header = List(
      PgnCodec.encodeHeader("Event", "πChess Game"),
      PgnCodec.encodeHeader("Site", "?"),
      PgnCodec.encodeHeader("Date", "????.??.??"),
      PgnCodec.encodeHeader("White", "?"),
      PgnCodec.encodeHeader("Black", "?"),
      PgnCodec.encodeHeader("Result", result)
    ).mkString("\n")
    val movetext = formatMovetext(moveLog, result)
    s"$header\n\n$movetext"

  private def formatMovetext(
      moveLog: List[(Color, String)],
      result: String
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
    if moves.isEmpty then result else s"$moves $result"

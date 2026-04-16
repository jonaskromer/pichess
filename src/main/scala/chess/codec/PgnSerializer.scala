package chess.codec

import chess.model.board.GameStatus
import chess.model.piece.Color
import zio.{Clock, UIO}

import java.time.format.DateTimeFormatter

object PgnSerializer:

  private val pgnDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd")

  /** Serialize a move log + final status to a PGN string. The Date header
    * is read from `Clock.currentDateTime` so the function is pure given
    * the ZIO clock context — tests can control it via `TestClock`.
    */
  def serialize(
      moveLog: List[(Color, String)],
      status: GameStatus
  ): UIO[String] =
    Clock.currentDateTime.map { now =>
      val result = PgnCodec.encodeResult(status)
      val date = now.toLocalDate.format(pgnDateFormat)
      val header = List(
        PgnCodec.encodeHeader("Event", "πChess Game"),
        PgnCodec.encodeHeader("Site", "Local"),
        PgnCodec.encodeHeader("Date", date),
        PgnCodec.encodeHeader("Round", "1"),
        PgnCodec.encodeHeader("White", "Player 1"),
        PgnCodec.encodeHeader("Black", "Player 2"),
        PgnCodec.encodeHeader("Result", result)
      ).mkString("\n")
      val movetext = formatMovetext(moveLog, result)
      s"$header\n\n$movetext"
    }

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

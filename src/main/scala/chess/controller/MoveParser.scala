package chess.controller

import chess.model.board.{Move, Position}
import chess.model.GameError

object MoveParser:
  private val hint = "Expected format: 'e2 e4' (column a–h, row 1–8)"
  private val posPattern = """^([a-h])([1-8])$""".r

  def parse(input: String): Either[GameError, Move] =
    input.trim.split("\\s+").toList match
      case List(fromStr, toStr) =>
        for
          from <- parsePosition(fromStr)
          to <- parsePosition(toStr)
        yield Move(from, to)
      case _ => Left(GameError.ParseError(s"Invalid input. $hint"))

  private def parsePosition(s: String): Either[GameError, Position] =
    s match
      case posPattern(colStr, rowStr) =>
        Right(Position(colStr.head, rowStr.head - '0'))
      case _ =>
        Left(GameError.ParseError(s"'$s' is not a valid position. $hint"))

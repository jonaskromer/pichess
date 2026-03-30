package chess.controller

import chess.model.board.{Move, Position}

object MoveParser:
  private val hint = "Expected format: 'e2 e4' (column a–h, row 1–8)"
  private val posPattern = """^([a-h])([1-8])$""".r

  def parse(input: String): Either[String, Move] =
    input.trim.split("\\s+").toList match
      case List(fromStr, toStr) =>
        for
          from <- parsePosition(fromStr)
          to <- parsePosition(toStr)
        yield Move(from, to)
      case _ => Left(s"Invalid input. $hint")

  private def parsePosition(s: String): Either[String, Position] =
    s match
      case posPattern(colStr, rowStr) =>
        Right(Position(colStr.head, rowStr.head - '0'))
      case _ =>
        Left(s"'$s' is not a valid position. $hint")

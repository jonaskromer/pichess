package chess.codec

import chess.model.GameError
import chess.model.board.GameState
import chess.model.piece.Color
import chess.model.rules.Game
import chess.notation.SanSerializer
import zio.*

object PgnParser:

  case class PgnGame(
      headers: Map[String, String],
      state: GameState,
      moveLog: List[(Color, String)]
  )

  def parse(input: String): IO[GameError, PgnGame] =
    val lines = input.linesIterator.toList
    val (headerLines, rest) = lines.span(_.startsWith("["))
    val headers = headerLines.flatMap(parseHeader).toMap
    val movetext = rest.mkString(" ").trim
    val fenHeader = headers.get("FEN")
    for
      initialState <- fenHeader match
        case Some(fen) =>
          ZIO.fromEither(FenParserRegex.parse(fen)).mapError(GameError.ParseError(_))
        case None => ZIO.succeed(GameState.initial)
      sanMoves = extractMoves(movetext)
      result <- replayMoves(initialState, sanMoves)
    yield PgnGame(headers, result._1, result._2)

  private val headerPattern = """\[(\w+)\s+"([^"]*)"\]""".r

  private def parseHeader(line: String): Option[(String, String)] =
    line match
      case headerPattern(key, value) => Some(key -> value)
      case _                         => None

  private val moveNumberPattern = """\d+\.""".r
  private val resultTokens = Set("1-0", "0-1", "1/2-1/2", "*")
  private val commentPattern = """\{[^}]*\}""".r
  private val nagPattern = """\$\d+""".r

  private def extractMoves(movetext: String): List[String] =
    val cleaned = commentPattern.replaceAllIn(movetext, " ")
    val withoutNags = nagPattern.replaceAllIn(cleaned, " ")
    val withoutNumbers = moveNumberPattern.replaceAllIn(withoutNags, " ")
    withoutNumbers
      .split("\\s+")
      .map(_.trim)
      .filter(t => t.nonEmpty && !resultTokens.contains(t))
      .toList

  private def replayMoves(
      initial: GameState,
      sanMoves: List[String]
  ): IO[GameError, (GameState, List[(Color, String)])] =
    ZIO.foldLeft(sanMoves)((initial, List.empty[(Color, String)])) {
      case ((state, log), san) =>
        for
          move <- chess.controller.MoveParser.parse(san, state)
          canonicalSan <- SanSerializer.toSan(move, state)
          newState <- Game.applyMove(state, move)
        yield (newState, log :+ (state.activeColor, canonicalSan))
    }

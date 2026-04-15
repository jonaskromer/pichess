package chess.codec

import chess.model.GameError
import chess.model.board.{GameState, Move}
import chess.model.rules.Game
import zio.*

object PgnParser:

  case class PgnGame(
      headers: Map[String, String],
      initialState: GameState,
      history: List[(Move, GameState)]
  ):
    def state: GameState = history.lastOption.map(_._2).getOrElse(initialState)
    def moves: List[Move] = history.map(_._1)

  def parse(input: String): IO[GameError, PgnGame] =
    val lines = input.linesIterator.toList
    val (headerLines, rest) = lines.span(_.startsWith("["))
    val headers = headerLines.flatMap(PgnCodec.decodeHeader).toMap
    val movetext = rest.mkString(" ").trim
    val fenHeader = headers.get("FEN")
    for
      initialState <- fenHeader match
        case Some(fen) =>
          ZIO
            .fromEither(FenParserRegex.parse(fen))
            .mapError(GameError.ParseError(_))
        case None => ZIO.succeed(GameState.initial)
      sanMoves = extractMoves(movetext)
      history <- replayMoves(initialState, sanMoves)
    yield PgnGame(headers, initialState, history)

  private val moveNumberPattern = """\d+\.""".r
  private val commentPattern = """\{[^}]*\}""".r
  private val nagPattern = """\$\d+""".r

  private def extractMoves(movetext: String): List[String] =
    val cleaned = commentPattern.replaceAllIn(movetext, " ")
    val withoutNags = nagPattern.replaceAllIn(cleaned, " ")
    val withoutNumbers = moveNumberPattern.replaceAllIn(withoutNags, " ")
    withoutNumbers
      .split("\\s+")
      .map(_.trim)
      .filter(t => t.nonEmpty && !PgnCodec.resultTokens.contains(t))
      .toList

  private def replayMoves(
      initial: GameState,
      sanMoves: List[String]
  ): IO[GameError, List[(Move, GameState)]] =
    ZIO
      .foldLeft(sanMoves)((initial, List.empty[(Move, GameState)])) {
        case ((state, history), san) =>
          for
            move <- chess.controller.MoveParser.parse(san, state)
            newState <- Game.applyMove(state, move)
          yield (newState, history :+ (move, newState))
      }
      .map(_._2)

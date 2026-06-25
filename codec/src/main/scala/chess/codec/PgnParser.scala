package chess.codec

import zio.*

import chess.model.GameError
import chess.model.board.{GameState, Move}
import chess.model.rules.Game

object PgnParser:

  case class PgnGame(
      headers: Map[String, String],
      initialState: GameState,
      history: List[(Move, GameState)],
      annotations: List[MoveAnnotation] = Nil
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
        case Some(fen) => FenParserRegex.parse(fen)
        case None      => ZIO.succeed(GameState.initial)
      parsed = tokenize(movetext)
      history <- replayMoves(initialState, parsed.map(_.san))
    yield PgnGame(headers, initialState, history, parsed.map(_.ann))

  private final case class RawMove(san: String, ann: MoveAnnotation)

  // Ordered alternation: a `{comment}`, a `$NAG`, a move number (`12.` / `12...`),
  // or any other bare token (a SAN, a result, or a stray glyph). Move-number
  // must precede the bare-token branch so `1.` isn't swallowed as a SAN.
  private val tokenPattern =
    """\{[^}]*\}|\$\d+|\d+\.(?:\.\.)?|[^\s{}]+""".r
  private val moveNumberPattern = """\d+\.(?:\.\.)?""".r
  // SAN with a glued assessment glyph, e.g. `Nf3!`, `e4?!` (Qh5+ has no glyph).
  private val glyphSuffix = """^(.*?)([!?]+)$""".r

  /** Tokenize movetext into moves, preserving NAGs and `[%clk]`/`[%emt]` clock
    * comments and attaching each to the move it follows. Move numbers and result
    * tokens are dropped; a comment/NAG before any move is ignored.
    */
  private def tokenize(movetext: String): List[RawMove] =
    tokenPattern
      .findAllIn(movetext)
      .foldLeft(Vector.empty[RawMove]) { (acc, tok) =>
        if tok.startsWith("{") then
          updateLast(acc, m => m.copy(ann = mergeComment(m.ann, tok)))
        else if tok.startsWith("$") then
          updateLast(acc, m => m.copy(ann = m.ann.copy(nag = parseNag(tok))))
        else if moveNumberPattern.matches(tok) ||
          PgnCodec.resultTokens.contains(tok)
        then acc
        else
          val (san, glyphNag) = splitGlyph(tok)
          if san.isEmpty then acc
          else acc :+ RawMove(san, MoveAnnotation(nag = glyphNag))
      }
      .toList

  private def updateLast(
      acc: Vector[RawMove],
      f: RawMove => RawMove
  ): Vector[RawMove] =
    if acc.isEmpty then acc else acc.updated(acc.size - 1, f(acc.last))

  private def mergeComment(
      ann: MoveAnnotation,
      comment: String
  ): MoveAnnotation =
    ann.copy(
      clockMs = PgnCodec.extractClock(comment).orElse(ann.clockMs),
      emtMs = PgnCodec.extractEmt(comment).orElse(ann.emtMs)
    )

  private def parseNag(tok: String): Option[Int] = tok.drop(1).toIntOption

  private def splitGlyph(tok: String): (String, Option[Int]) =
    tok match
      case glyphSuffix(san, glyph) => (san, Nag.code(glyph))
      case _                       => (tok, None)

  private def replayMoves(
      initial: GameState,
      sanMoves: List[String]
  ): IO[GameError, List[(Move, GameState)]] =
    ZIO
      .foldLeft(sanMoves)((initial, List.empty[(Move, GameState)])) {
        case ((state, history), san) =>
          for
            move <- chess.notation.MoveParser.parse(san, state)
            newState <- Game.applyMove(state, move)
          yield (newState, history :+ (move, newState))
      }
      .map(_._2)

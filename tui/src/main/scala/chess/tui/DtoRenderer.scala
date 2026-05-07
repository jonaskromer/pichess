package chess.tui

import chess.api.{BoardStateDto, MoveEntryDto, SquareDto}

/** Pretty-prints a `BoardStateDto` to a console string.
  *
  * The web-ui has its own DOM-shaped renderer and the existing
  * `chess.view.BoardView` renders a richer `GameState`. The TUI client
  * speaks the wire DTO, so we render directly from that — no FEN
  * round-trip — keeping a single HTTP call per command.
  */
object DtoRenderer:

  // ANSI escapes
  private val lightBg = "[48;2;235;225;200m"
  private val darkBg = "[48;2;150;185;155m"
  private val whiteFg = "[1;97m"
  private val blackFg = "[1;30m"
  private val checkFg = "[1;91m"
  private val reset = "[0m"

  // Unicode glyphs keyed by the gateway's PieceType strings (lowercase).
  private val whiteGlyphs = Map(
    "king"   -> "♔",
    "queen"  -> "♕",
    "rook"   -> "♖",
    "bishop" -> "♗",
    "knight" -> "♘",
    "pawn"   -> "♙"
  )
  private val blackGlyphs = Map(
    "king"   -> "♚",
    "queen"  -> "♛",
    "rook"   -> "♜",
    "bishop" -> "♝",
    "knight" -> "♞",
    "pawn"   -> "♟"
  )

  def render(dto: BoardStateDto, flipped: Boolean = false): String =
    val board = renderBoard(dto, flipped)
    val log = renderMoveLog(dto.moveLog)
    val status = renderStatus(dto)
    val parts = List(board, log, status).filter(_.nonEmpty)
    parts.mkString("\n\n")

  private def renderBoard(dto: BoardStateDto, flipped: Boolean): String =
    val byPos: Map[String, SquareDto] = dto.squares.map(s => s.pos -> s).toMap
    val cols: Seq[Char] =
      if flipped then ('a' to 'h').toList.reverse else ('a' to 'h').toList
    val rows: Seq[Int] = if flipped then 1 to 8 else 8 to 1 by -1
    val colLabels = " " + cols.map(c => s" $c ").mkString + "\n"
    val ranks = rows.map { row =>
      val squares = cols.map { col =>
        val pos = s"$col$row"
        val isDark = (col - 'a' + row) % 2 == 1
        val bg = if isDark then darkBg else lightBg
        byPos.get(pos) match
          case None => s"$bg   $reset"
          case Some(sq) =>
            sq.piece match
              case None => s"$bg   $reset"
              case Some(piece) =>
                val isCheckedKing =
                  dto.inCheck && dto.checkedKingPos.contains(pos)
                val color = sq.pieceColor.getOrElse("white")
                val fg =
                  if isCheckedKing then checkFg
                  else if color == "white" then whiteFg
                  else blackFg
                val glyph = glyphFor(piece, color)
                s"$bg$fg $glyph $reset"
      }
      s"$row${squares.mkString} $row"
    }
    colLabels + ranks.mkString("\n") + "\n" + colLabels +
      s"\n  ${dto.activeColor} to move"

  private def glyphFor(piece: String, color: String): String =
    val table = if color == "white" then whiteGlyphs else blackGlyphs
    table.getOrElse(piece, piece.headOption.map(_.toUpper.toString).getOrElse("?"))

  private def renderMoveLog(log: List[MoveEntryDto]): String =
    if log.isEmpty then ""
    else
      // Group into pairs (white, black) and number them so the log reads
      // like a PGN excerpt rather than a flat list.
      val grouped =
        log
          .grouped(2)
          .zipWithIndex
          .map { case (pair, idx) =>
            val moveNumber = idx + 1
            // grouped(2) yields lists of size 1 or 2 only; pick the right
            // shape directly so scoverage doesn't see an unreachable
            // catch-all.
            if pair.sizeIs == 2 then
              s"$moveNumber. ${pair.head.san} ${pair.last.san}"
            else s"$moveNumber. ${pair.head.san}"
          }
          .toList
      "Moves: " + grouped.mkString(" ")

  private def renderStatus(dto: BoardStateDto): String =
    val statusLine = dto.status.kind match
      case "playing"     => "Game in progress"
      case "checkmate"   =>
        val winner = dto.status.winner.getOrElse("?")
        s"CHECKMATE — $winner wins"
      case "draw"        =>
        val reason = dto.status.reason.getOrElse("?")
        s"DRAW ($reason)"
      case "resignation" =>
        val winner = dto.status.winner.getOrElse("?")
        s"RESIGNATION — $winner wins"
      case other         => s"Status: $other"
    val errLine = dto.error.fold("")(e => s"Error: $e")
    List(statusLine, errLine).filter(_.nonEmpty).mkString("\n")

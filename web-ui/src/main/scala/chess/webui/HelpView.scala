package chess.webui

import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Modifier

import chess.webui.components.Components

// Mirror of tui/src/main/scala/chess/view/HelpView.scala — keep in sync.
// The TUI version is plain monospaced text; this one is Laminar so the
// browser can render it as a proper docs page with semantic tables and
// sections inside the SPA (no full reload).
object HelpView:

  def render(): HtmlElement =
    div(
      className := "help-page",
      commandsSection,
      importExportSection,
      fenSection,
      pgnSection,
      moveNotationSection,
      rulesSection
    )

  /** Each section is its own paper panel — first child is the paper-layer
    * SVG backdrop (drives the crumple + grid via CSS variables), then the
    * photo-corner tape, real content stacking above via `.help-section`'s
    * `isolation: isolate`. */
  private def section(
      title: String,
      tape: Seq[String],
      body: HtmlElement*
  ): HtmlElement =
    sectionTag(
      className := "help-section",
      Components.paperLayer(),
      Components.tapeCorners(tape),
      h2(Components.newsprintClip(heading = true)(title)),
      body
    )

  // Variadic-Modifier signature so each row can mix raw strings and `code(...)`
  // elements freely. Strings are auto-lifted to text nodes by Laminar.
  private def row(cmd: String, desc: Modifier[HtmlElement]*): HtmlElement =
    tr(td(code(cmd)), td(desc*))

  /** Newspaper-clipping pill — the shared [[Components.newsprintClip]]. */
  private def code(text: String): HtmlElement =
    Components.newsprintClip()(text)

  /** Definition-list term as a newspaper clipping (Special Elite + cut). */
  private def term(label: String): HtmlElement =
    dt(Components.newsprintClip()(label))

  /** Block-sized newspaper clipping for the multi-line `<pre>` listing. */
  private def helpPre(text: String): HtmlElement =
    Components.newsprintClip(block = true)(text)

  private val commandsSection: HtmlElement =
    section(
      "Commands",
      Seq("tl", "br"),
      table(
        className := "help-table",
        row("<from> <to>", "Move a piece (e.g. ", code("e2 e4"), ")"),
        row("load FEN|PGN|JSON", "Load a game (format is auto-detected)"),
        row(
          "export fen|pgn|json",
          "Export the current game in the given format"
        ),
        row("undo", "Undo the last move"),
        row("redo", "Redo the last undone move"),
        row("draw", "Claim a draw (50-move rule or threefold repetition)"),
        row(
          "forfeit",
          "Resign — the side to move loses; the opponent is the winner"
        ),
        row("flip", "Flip the board (toggle White/Black perspective)"),
        row("new", "Start a fresh game from the initial position")
      )
    )

  private val importExportSection: HtmlElement =
    section(
      "Import / Export",
      Seq("tr"),
      p(
        "The ",
        code("load"),
        " command accepts FEN, PGN, or JSON — the format is detected automatically. ",
        code("export"),
        " requires a format argument."
      ),
      helpPre(
        """load rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1
load 1. e4 e5 2. Nf3 Nc6 *
load {"board": {...}, "activeColor": "white", ...}
export fen
export pgn
export json"""
      )
    )

  private val fenSection: HtmlElement =
    section(
      "FEN (Forsyth-Edwards Notation)",
      Seq("tl", "tr", "br"),
      p("FEN encodes a complete board position as a single line of text."),
      p(
        strong("Format: "),
        code(
          "<placement> <active> <castling> <en-passant> <halfmove> <fullmove>"
        )
      ),
      p(
        strong("Example: "),
        code("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"),
        " (after 1. e4)"
      ),
      dl(
        className := "help-dl",
        term("Placement"),
        dd(
          "8 ranks separated by /, from rank 8 (top) to rank 1. Letters are pieces (KQRBNPkqrbnp), digits are empty squares. Uppercase = White, lowercase = Black."
        ),
        term("Active"),
        dd(code("w"), " or ", code("b"), " — whose turn it is."),
        term("Castling"),
        dd(
          "Combination of ",
          code("K Q k q"),
          ", or ",
          code("-"),
          " for none."
        ),
        term("En passant"),
        dd("Target square (e.g. ", code("e3"), ") or ", code("-"), "."),
        term("Halfmove"),
        dd(
          "Moves since the last pawn push or capture (drives the 50-move rule)."
        ),
        term("Fullmove"),
        dd("Incremented after Black's move; starts at 1.")
      )
    )

  private val pgnSection: HtmlElement =
    section(
      "PGN (Portable Game Notation)",
      Seq("bl", "br"),
      p("PGN records a full game as a sequence of SAN moves."),
      p(strong("Example: "), code("1. e4 e5 2. Nf3 Nc6 *")),
      p(
        "Move numbers (",
        code("1."),
        ", ",
        code("2."),
        ", …) and result tokens (",
        code("1-0"),
        ", ",
        code("0-1"),
        ", ",
        code("*"),
        ") are handled. Comments in ",
        code("{braces}"),
        " and NAG annotations (",
        code("$1"),
        ", ",
        code("$2"),
        ") are ignored."
      )
    )

  private val moveNotationSection: HtmlElement =
    section(
      "Move Notation",
      Seq("tr", "bl"),
      p("Both coordinate and Standard Algebraic Notation (SAN) are accepted."),
      table(
        className := "help-table",
        row(
          "Coordinate",
          code("e2 e4"),
          " ",
          code("e2e4"),
          " ",
          code("e2-e4"),
          " ",
          code("e7 e8=Q")
        ),
        row("Pawn push", code("e4"), " ", code("d5")),
        row("Pawn capture", code("exd5"), " ", code("cxb4")),
        row(
          "Piece move",
          code("Nf3"),
          " ",
          code("Bc4"),
          " ",
          code("Rd1"),
          " ",
          code("Qd8"),
          " ",
          code("Ke2")
        ),
        row("Piece capture", code("Nxf3"), " ", code("Bxc6")),
        row(
          "Disambiguation",
          code("Nbd2"),
          " ",
          code("N1f3"),
          " ",
          code("Raxd5"),
          " (file, rank, or both)"
        ),
        row(
          "Promotion",
          code("e8=Q"),
          " ",
          code("exd8=R"),
          " (=Q, =R, =B, =N)"
        ),
        row(
          "Castling",
          code("O-O"),
          " kingside  ",
          code("O-O-O"),
          " queenside"
        ),
        row("Check / mate", code("Nf3+"), " ", code("Qxf7#")),
        row("Piece letters", "N=Knight  B=Bishop  R=Rook  Q=Queen  K=King")
      )
    )

  private val rulesSection: HtmlElement =
    section(
      "Implemented Rules",
      Seq("tl"),
      dl(
        className := "help-dl",
        term("Pawn"),
        dd(
          "One square forward; two from the starting rank; diagonal capture; en passant; promotion on the back rank."
        ),
        term("Rook"),
        dd("Any distance horizontally or vertically; blocked by pieces."),
        term("Bishop"),
        dd("Any distance diagonally; blocked by pieces."),
        term("Queen"),
        dd("Any distance in any direction; blocked by pieces."),
        term("Knight"),
        dd("L-shape (2+1 squares); jumps over pieces."),
        term("King"),
        dd(
          "One square in any direction; castling (",
          code("O-O"),
          " / ",
          code("O-O-O"),
          ")."
        ),
        term("Check"),
        dd(
          "Moves leaving your own king in check are rejected. The checked king is highlighted in both TUI and GUI."
        ),
        term("Castling"),
        dd(
          "King moves two squares toward the rook; rook jumps over. Requires neither piece moved, the path clear, and no check on the king's path."
        ),
        term("Checkmate"),
        dd(
          "Detected automatically; the game ends and the winner is announced. SAN appends ",
          code("#"),
          "."
        ),
        term("Stalemate"),
        dd(
          "Detected automatically; drawn when the side to move has no legal move but is not in check."
        ),
        term("50-move rule"),
        dd(
          "Claim a draw with ",
          code("draw"),
          " after 50 moves with no pawn push or capture."
        ),
        term("Insufficient material"),
        dd(
          "Drawn automatically when neither side can checkmate (K vs K, K+B vs K, K+N vs K, K+B vs K+B with same-colored bishops)."
        ),
        term("Threefold repetition"),
        dd(
          "Claim a draw with ",
          code("draw"),
          " when the same position has occurred at least three times."
        ),
        term("Fivefold repetition"),
        dd("Drawn automatically when the same position occurs five times."),
        term("Turn order"),
        dd("White moves first, then alternates.")
      )
    )

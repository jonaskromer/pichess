package chess.webui

import chess.api.{
  BoardStateDto,
  Endpoints,
  ErrorDto,
  ExportResponse,
  GameStatusDto,
  LoadRequest,
  MoveEntryDto,
  MoveRequest,
  SquareDto,
  StateResponse
}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import sttp.client3.FetchBackend
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.json.*

object Main:

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), App())

  private case class PendingPromotion(from: String, to: String)

  // --------------------------------------------------------------------------
  // Reactive state
  // --------------------------------------------------------------------------

  private val stateVar: Var[Option[BoardStateDto]] = Var(None)
  private val dragSourceVar: Var[Option[String]] = Var(None)
  private val pendingPromotionVar: Var[Option[PendingPromotion]] = Var(None)
  private val toastVar: Var[Option[String]] = Var(None)
  private val goodbyeVar: Var[Boolean] = Var(false)
  private val flippedVar: Var[Boolean] = Var(false)
  private val loadOpenVar: Var[Boolean] = Var(false)
  private val loadInputVar: Var[String] = Var("")
  private val exportVar: Var[Option[ExportResponse]] = Var(None)
  // Help is rendered as an in-SPA view — not a separate route — so that the
  // browser back button returns to the game without a full page reload.
  // We sync this var with `location.hash` so deep-links (#help) and the back
  // button just work via hashchange events.
  private val helpOpenVar: Var[Boolean] = Var(false)

  // Generic "are you sure?" prompt. Set to `Some(...)` to display; the modal
  // wires the confirm button to `action` and clears itself on confirm/cancel.
  private case class ConfirmRequest(
      title: String,
      message: String,
      confirmLabel: String,
      destructive: Boolean,
      action: () => Unit
  )
  private val confirmVar: Var[Option[ConfirmRequest]] = Var(None)
  private def askConfirm(req: ConfirmRequest): Unit = confirmVar.set(Some(req))

  // Theme — see Logic.Theme + Logic.decideInitialTheme for the policy. The
  // synchronous bootstrap script in HtmlPage.scala already applies the dark
  // class on first paint to avoid FOUT; we re-derive it here so the toggle
  // and themeVar are in sync from boot.
  private val storageKey = "pichess.theme"
  private val themeVar: Var[Logic.Theme] = Var(currentTheme())

  private def currentTheme(): Logic.Theme =
    val stored = Option(dom.window.localStorage.getItem(storageKey))
    val prefersDark =
      dom.window.matchMedia("(prefers-color-scheme: dark)").matches
    Logic.decideInitialTheme(stored, prefersDark)

  private def applyTheme(t: Logic.Theme): Unit =
    val cl = dom.document.documentElement.classList
    if t == Logic.Theme.Dark then cl.add("dark") else cl.remove("dark")

  private def toggleTheme(): Unit =
    val next = themeVar.now() match
      case Logic.Theme.Light => Logic.Theme.Dark
      case Logic.Theme.Dark  => Logic.Theme.Light
    themeVar.set(next)
    dom.window.localStorage.setItem(storageKey, next.toString.toLowerCase)
    applyTheme(next)

  private def themeToggleButton(): HtmlElement =
    button(
      // Don't include "header-link" — that class was rewritten as a
      // newspaper-cutout style; the toggle wants the post-it look from
      // the .theme-toggle-btn rule alone.
      className := "theme-toggle-btn",
      aria.label := "Toggle dark mode",
      `type` := "button",
      onClick --> { _ => toggleTheme() },
      // Glyph reflects the *target* mode (sun = "click to go light",
      // moon = "click to go dark"), the convention every theme toggle uses.
      child.text <-- themeVar.signal.map {
        case Logic.Theme.Light => "☾"
        case Logic.Theme.Dark  => "☼"
      }
    )

  // --------------------------------------------------------------------------
  // Top-level component
  // --------------------------------------------------------------------------

  private def App(): HtmlElement =
    div(
      onMountCallback { _ =>
        // The HtmlPage bootstrap script already set the dark class before
        // first paint; this call is the safety net for the (rare) case
        // where the script ran before localStorage was readable.
        applyTheme(themeVar.now())
        fetchState()
        connectEvents()
        syncHelpFromHash()
        dom.window.addEventListener(
          "hashchange",
          (_: dom.Event) => syncHelpFromHash()
        )
      },
      child <-- goodbyeVar.signal.map {
        case true  => goodbyeScreen()
        case false => mainUi()
      }
    )

  private def syncHelpFromHash(): Unit =
    helpOpenVar.set(dom.window.location.hash == "#help")

  private def goodbyeScreen(): HtmlElement =
    div(
      styleAttr := "display:flex;justify-content:center;align-items:center;" +
        "height:100vh;font-size:24px;color:#f7a072;",
      "Goodbye!"
    )

  private def mainUi(): HtmlElement =
    div(
      className := "app-shell",
      pageBackground(),
      header(),
      child <-- helpOpenVar.signal.map {
        case true  => HelpView.render()
        case false => gameBody()
      },
      promotionOverlay(),
      loadModal(),
      exportModal(),
      confirmModal(),
      toastElement()
    )

  // --------------------------------------------------------------------------
  // Paper backgrounds — crumpled SVG behind the page, clean SVG behind big
  // dialogs. Both are rendered as inline <use> references so the SVG's
  // --paper-color / --grid-color vars cascade from :root, which lets the
  // dark-mode toggle retint the paper without duplicating SVG files.
  // --------------------------------------------------------------------------

  // Paper SVG sprites — both square (so wrinkle texture stays native-scale
  // when sliced onto any container shape, no aspect distortion) and both
  // sharing the same crumple lighting filter via colour-dodge so they
  // re-tint cleanly across themes via --crumple-highlight.
  //
  // paperGridHref     — paper + ruled grid; used for panels and the header
  //                     so they read as actual notebook pages
  // paperGridlessHref — paper without ruling; used as the page background
  //                     so the page doesn't tile a busy grid behind every
  //                     panel that already has its own grid
  // Document-internal hrefs — the symbols are inlined in HtmlPage.scala's
  // sprite host so cross-document `<use href="external.svg#id">` (which
  // has flaky CSS-var cascade through <pattern> / <feDiffuseLighting>)
  // is no longer needed. Same-document `<use href="#id">` cascades vars
  // reliably, so --paper-color / --grid-color / --crumple-highlight
  // overrides actually take effect.
  private val paperGridHref = "#paper-crumpled-grid-square"
  private val paperGridlessHref = "#paper-crumpled-square"

  private def pageBackground(): HtmlElement =
    div(
      className := "page-bg",
      svg.svg(
        svg.viewBox := "0 0 600 600",
        svg.preserveAspectRatio := "xMidYMid slice",
        svg.use(svg.href := paperGridlessHref)
      )
    )

  // Defaults to grid (panels + header want ruling). Pass grid = false for
  // any surface that should sit on plain crumpled paper. The legacy
  // `crumpled` parameter is retained as an unused alias for source-compat
  // with existing callsites; everything is crumpled now anyway.
  private def paperLayer(
      crumpled: Boolean = false,
      grid: Boolean = true
  ): HtmlElement =
    val _ = crumpled
    div(
      className := "paper-layer",
      svg.svg(
        svg.viewBox := "0 0 600 600",
        svg.preserveAspectRatio := "xMidYMid slice",
        svg.use(svg.href := (if grid then paperGridHref else paperGridlessHref))
      )
    )

  private def gameBody(): HtmlElement =
    div(
      className := "app",
      boardArea(),
      sidebar()
    )

  // --------------------------------------------------------------------------
  // Header
  // --------------------------------------------------------------------------

  private def header(): HtmlElement =
    headerTag(
      className := "header",
      paperLayer(crumpled = true),
      div(
        className := "header-brand",
        // Inline SVG so the host's CSS variables (--peach-color, --leaf-color,
        // …) cascade through the <use> reference into the symbol's paths.
        // <img src=…> would isolate the SVG and prevent any theming. The
        // viewBox must match the symbol's so the right and bottom of the
        // peach aren't clipped by the host's coordinate system.
        svg.svg(
          svg.viewBox := "-3 -3 43 44",
          svg.cls := "header-logo",
          svg.use(svg.href := "/web/peach.svg#peach")
        ),
        span(
          child.text <-- helpOpenVar.signal.map(o =>
            if o then "piChess Help" else "piChess"
          )
        )
      ),
      div(className := "header-spacer"),
      div(
        className := "header-actions",
        children <-- helpOpenVar.signal.map(o =>
          if o then helpHeaderActions else gameHeaderActions
        )
      )
    )

  private def gameHeaderActions: List[HtmlElement] = List(
    themeToggleButton(),
    button(
      className := "header-new-btn",
      onClick --> { _ => askConfirm(confirmNewGame) },
      "New Game"
    ),
    a(
      className := "header-link",
      href := "#help",
      "Help"
    ),
    a(
      className := "header-link",
      href := "/docs",
      target := "_blank",
      rel := "noopener noreferrer",
      "Docs ↗"
    )
  )

  private def helpHeaderActions: List[HtmlElement] = List(
    themeToggleButton(),
    a(
      className := "header-link",
      href := "#",
      "← Game"
    ),
    a(
      className := "header-link",
      href := "/docs",
      target := "_blank",
      rel := "noopener noreferrer",
      "Docs ↗"
    )
  )

  // --------------------------------------------------------------------------
  // Board
  // --------------------------------------------------------------------------

  private def boardArea(): HtmlElement =
    // Board sits on a sheet of crumpled paper. statusIndicator at the top
    // (panel header). board-row puts board-wrapper next to capturedPile —
    // captured pieces stick to the right of the board, mirroring the
    // rank-label gutter on the left.
    div(
      className := "board-area",
      div(
        className := "board-paper",
        paperLayer(crumpled = true),
        statusIndicator(),
        div(
          className := "board-row",
          div(
            className := "board-wrapper",
            rankLabels(),
            board(),
            fileLabels()
          ),
          capturedPile()
        )
      )
    )

  // Captured pieces in the right gutter, split into two sections by who
  // took whom. Captures land on the side of the player that took them, so
  // black pieces (taken by white) appear on white's half and vice versa.
  // When the board is flipped, both assignments invert.
  //
  // Each section is its own flex column with overlap + nth-child rotation
  // jitter so the two stacks read as separate physical piles rather than
  // one continuous list.
  private def capturedPile(): HtmlElement =
    val signal = stateVar.signal.combineWith(flippedVar.signal).map {
      case (None, _) => (List.empty[String], List.empty[String], false)
      case (Some(s), flipped) =>
        val (whiteLost, blackLost) = Logic.capturedFromSquares(s.squares)
        (whiteLost, blackLost, flipped)
    }
    div(
      className := "captured-pile",
      children <-- signal.map { case (whiteLost, blackLost, flipped) =>
        // Default (white at bottom): white-took-black → black pieces on
        // bottom; black-took-white → white pieces on top. Flipped: invert.
        val (topPieces, topColor, bottomPieces, bottomColor) =
          if flipped then (blackLost, "black", whiteLost, "white")
          else (whiteLost, "white", blackLost, "black")
        // Top section: DOM order reversed so newest is at the visual top
        // (farthest from board) AND the painting order naturally puts older
        // pieces on top of newer (user-requested z-flip for that pile).
        // Bottom section uses regular DOM order: newest paints on top of
        // older — physical-stacking convention.
        List(
          div(
            className := "captured-section captured-section-top",
            topPieces.reverse.map(renderCapturedPiece(_, topColor))
          ),
          div(
            className := "captured-section captured-section-bottom",
            bottomPieces.map(renderCapturedPiece(_, bottomColor))
          )
        )
      }
    )

  private def renderCapturedPiece(name: String, color: String): HtmlElement =
    span(className := s"captured-piece $color-piece", pieceSvg(name))

  private def rankLabels(): HtmlElement =
    div(
      className := "rank-labels",
      children <-- flippedVar.signal.map { flipped =>
        val ranks =
          if flipped then (1 to 8).toList else (8 to 1 by -1).toList
        ranks.map(r => div(r.toString))
      }
    )

  private def fileLabels(): HtmlElement =
    div(
      className := "file-labels",
      children <-- flippedVar.signal.map { flipped =>
        val files =
          if flipped then ('a' to 'h').toList.reverse
          else ('a' to 'h').toList
        files.map(c => div(c.toString))
      }
    )

  private def board(): HtmlElement =
    div(
      className := "board",
      children <-- stateVar.signal.combineWith(flippedVar.signal).map {
        case (None, _) => List.empty
        case (Some(s), flipped) =>
          val squares = if flipped then s.squares.reverse else s.squares
          squares.map(renderSquare(s, _))
      }
    )

  private def renderSquare(
      state: BoardStateDto,
      sq: SquareDto
  ): HtmlElement =
    val isChecked = state.checkedKingPos.contains(sq.pos)
    val squareClasses =
      s"square ${sq.squareColor}" + (if isChecked then " in-check" else "")
    div(
      className := squareClasses,
      dataAttr("pos") := sq.pos,
      onDragOver --> { e =>
        e.preventDefault()
        // Tell the browser this is a "move" target — without this, the
        // cursor over a drop target shows the OS default (often the
        // text-select / I-beam shape) rather than the move icon.
        e.dataTransfer.dropEffect =
          "move".asInstanceOf[dom.DataTransferDropEffectKind]
      },
      onDrop.preventDefault --> { _ => handleDrop(sq.pos, state) },
      sq.piece.map { name =>
        span(
          className := s"piece ${sq.pieceColor.getOrElse("")}-piece",
          draggable := true,
          onDragStart --> { e => handleDragStart(sq.pos, e) },
          onDragEnd --> { _ => dragSourceVar.set(None) },
          pieceSvg(name)
        )
      }
    )

  /** Render a chess piece as an inline SVG that pulls geometry from the shared
    * sprite at `/web/pieces/<name>.svg#<name>`. Inline SVG (rather than
    * `<img>`) is needed so the parent's `--piece-primary` and
    * `--piece-secondary` CSS variables cascade through the `<use>` reference
    * into the symbol's paths.
    *
    * The host SVG's viewBox matches each piece's source aspect ratio so the
    * browser can derive an intrinsic height when the surrounding CSS sets
    * `width: <fixed>; height: auto`. All pieces then share a common base width
    * while keeping their natural relative heights — kings tall, pawns short.
    */
  private def pieceSvg(name: String): SvgElement =
    svg.svg(
      svg.viewBox := pieceViewBox(name),
      svg.cls := "piece-svg",
      svg.use(svg.href := s"/web/pieces/$name.svg#$name")
    )

  // Source-coordinate viewBox per piece — copied from each unified SVG
  // file so the host SVG inherits its aspect ratio.
  private val pieceViewBox: Map[String, String] = Map(
    "pawn" -> "0 0 460.1 624.7",
    "rook" -> "0 0 498.5 747.5",
    "knight" -> "0 0 507.7 777.5",
    "bishop" -> "0 0 460.1 856.8",
    "queen" -> "0 0 544.3 1000.7",
    "king" -> "0 0 590.5 1259.6"
  )

  // --------------------------------------------------------------------------
  // Sidebar
  // --------------------------------------------------------------------------

  private def sidebar(): HtmlElement =
    div(
      className := "sidebar",
      // statusIndicator moved out of the sidebar — it now sits above the
      // board as a panel header (see boardArea below). Sidebar focuses on
      // the move log + action buttons.
      moveLogContainer(),
      controls()
    )

  private def statusIndicator(): HtmlElement =
    div(
      child <-- stateVar.signal.map {
        case None => emptyNode
        case Some(s) =>
          s.status.kind match
            case "checkmate"   => checkmateBanner(s.status)
            case "draw"        => drawBanner(s.status)
            case "resignation" => resignationBanner(s.status)
            case _             => turnIndicator(s)
      }
    )

  // Status panel-header — rendered inside .board-paper so it sits on the
  // same sheet as the board. No own paperLayer (board-paper provides it);
  // styled as an inline label rather than a standalone paper note.
  private def turnIndicator(s: BoardStateDto): HtmlElement =
    val name = if s.activeColor == "white" then "White" else "Black"
    div(
      className := "turn-indicator",
      div(className := s"turn-dot ${s.activeColor}"),
      span(s"$name to move")
    )

  private def banner(cls: String, text: String): HtmlElement =
    div(className := s"banner $cls", span(text))

  private def checkmateBanner(status: GameStatusDto): HtmlElement =
    val winner = status.winner.map(capitalize).getOrElse("Someone")
    banner("win", s"$winner wins by checkmate")

  private def drawBanner(status: GameStatusDto): HtmlElement =
    val reason =
      status.reason.map(Logic.humanizeDrawReason).getOrElse("agreement")
    banner("draw", s"Draw — $reason")

  private def resignationBanner(status: GameStatusDto): HtmlElement =
    val winner = status.winner.map(capitalize).getOrElse("Someone")
    banner("win", s"$winner wins by resignation")

  private def capitalize(s: String): String =
    if s.isEmpty then s else s.head.toUpper + s.tail

  private def moveLogContainer(): HtmlElement =
    // The "Moves" heading is rendered ON the paper (inside .move-log-paper)
    // rather than as a separate label above it — reads as a label scribbled
    // on the note itself. The yellow marker stripe (from CSS ::before mask)
    // sits behind the text.
    //
    // paperLayer can't go inside .move-log because absolute positioning
    // inside an overflow:auto container scrolls with the content. The
    // .move-log-paper wrapper hosts the SVG fixed behind the scrolling
    // viewport, while the move list scrolls above it.
    div(
      className := "move-log-container",
      div(
        className := "move-log-paper",
        paperLayer(crumpled = true),
        h2(className := "section-title", "Moves"),
        div(
          // Stable outer — OS wraps this on mount. Laminar's `children <--`
          // can't go on this element because OS rewrites the DOM under it
          // (.os-viewport > .os-padding > .os-contents), and Laminar would
          // start appending new children outside OS's wrapper while the
          // initial ones stay nested inside (the duplicate "no moves yet"
          // bug). The dynamic content lives on .move-log-inner instead;
          // OS doesn't touch that node, so Laminar updates it freely.
          className := "move-log",
          onMountCallback { ctx =>
            val OS = js.Dynamic.global.OverlayScrollbarsGlobal.OverlayScrollbars
            OS(
              ctx.thisNode.ref,
              js.Dynamic.literal(
                scrollbars = js.Dynamic.literal(theme = "os-theme-pichess")
              )
            )
          },
          div(
            className := "move-log-inner",
            children <-- stateVar.signal.map {
              case None    => List.empty
              case Some(s) => renderMoveLog(s.moveLog)
            }
          )
        )
      )
    )

  private def renderMoveLog(moves: List[MoveEntryDto]): List[HtmlElement] =
    if moves.isEmpty then
      List(div(className := "move-log-empty", "No moves yet"))
    else
      Logic.groupMovesByTwo(moves).map { case (num, white, blackOpt) =>
        val cells = List(
          span(className := "move-number", s"$num."),
          span(className := "move-san", white.san)
        ) ++ blackOpt.map(m => span(className := "move-san", m.san))
        div(className := "move-row", cells)
      }

  private def controls(): HtmlElement =
    val moveInputVar = Var("")
    div(
      className := "controls",
      form(
        idAttr := "moveForm",
        onSubmit.preventDefault --> { _ =>
          val v = moveInputVar.now().trim
          if v.nonEmpty then
            postMove(v)
            moveInputVar.set("")
        },
        input(
          tpe := "text",
          idAttr := "moveInput",
          placeholder := "e.g. e2e4 or Nf3",
          autoComplete := "off",
          spellCheck := false,
          controlled(
            value <-- moveInputVar.signal,
            onInput.mapToValue --> moveInputVar
          )
        ),
        button(tpe := "submit", "Move")
      ),
      sectionLabel("History"),
      div(
        className := "btn-row",
        secondaryButton("Undo", () => postUndo()),
        secondaryButton("Redo", () => postRedo()),
        secondaryButton("Draw", () => askConfirm(confirmDraw))
      ),
      sectionLabel("Board"),
      div(
        className := "btn-row",
        button(
          className := "secondary-btn",
          onClick --> { _ => flippedVar.update(!_) },
          child.text <-- flippedVar.signal.map(f =>
            if f then "Unflip" else "Flip"
          )
        )
      ),
      sectionLabel("Data"),
      div(
        className := "btn-row",
        secondaryButton("Load", () => loadOpenVar.set(true)),
        secondaryButton("FEN", () => doExport("fen")),
        secondaryButton("PGN", () => doExport("pgn")),
        secondaryButton("JSON", () => doExport("json"))
      ),
      sectionLabel("Game"),
      div(
        className := "btn-row",
        button(
          className := "secondary-btn",
          onClick --> { _ => askConfirm(confirmForfeit) },
          "Forfeit"
        ),
        button(
          className := "quit-btn",
          onClick --> { _ => askConfirm(confirmQuit) },
          "Quit"
        )
      )
    )

  private val confirmNewGame: ConfirmRequest = ConfirmRequest(
    title = "Start a new game?",
    message =
      "This discards the current game and resets to the starting position.",
    confirmLabel = "New Game",
    destructive = true,
    action = () => postNew()
  )

  private val confirmDraw: ConfirmRequest = ConfirmRequest(
    title = "Claim a draw?",
    message =
      "Only valid if the 50-move rule applies or the position has occurred at least three times.",
    confirmLabel = "Claim Draw",
    destructive = false,
    action = () => postDraw()
  )

  private val confirmQuit: ConfirmRequest = ConfirmRequest(
    title = "Shut down the server?",
    message =
      "This stops piChess and closes the page. Make sure to save anything you want to keep.",
    confirmLabel = "Quit",
    destructive = true,
    action = () => postQuit()
  )

  private val confirmForfeit: ConfirmRequest = ConfirmRequest(
    title = "Forfeit the game?",
    message = "The current player resigns; the opponent wins.",
    confirmLabel = "Forfeit",
    destructive = true,
    action = () => postForfeit()
  )

  private def sectionLabel(text: String): HtmlElement =
    div(className := "section-title", text)

  private def secondaryButton(
      label: String,
      onClickAction: () => Unit
  ): HtmlElement =
    button(
      className := "secondary-btn",
      onClick --> { _ => onClickAction() },
      label
    )

  // --------------------------------------------------------------------------
  // Overlays
  // --------------------------------------------------------------------------

  private def toastElement(): HtmlElement =
    div(
      idAttr := "toast",
      className <-- toastVar.signal.map(t =>
        if t.isDefined then "toast visible" else "toast"
      ),
      child.text <-- toastVar.signal.map(_.getOrElse(""))
    )

  private def promotionOverlay(): HtmlElement =
    div(
      idAttr := "promotionOverlay",
      className <-- pendingPromotionVar.signal.map(p =>
        if p.isDefined then "promotion-overlay visible" else "promotion-overlay"
      ),
      div(
        idAttr := "promotionDialog",
        className := "promotion-dialog",
        paperLayer(),
        children <-- pendingPromotionVar.signal
          .combineWith(stateVar.signal)
          .map {
            case (Some(p), Some(s)) => promotionChoices(p, s)
            case _                  => List.empty
          }
      )
    )

  private def promotionChoices(
      p: PendingPromotion,
      state: BoardStateDto
  ): List[HtmlElement] =
    state.squares.find(_.pos == p.from) match
      case None => List.empty
      case Some(sq) =>
        val colorClass = s"${sq.pieceColor.getOrElse("")}-piece"
        Logic.promotionChoices.map { case (key, name) =>
          div(
            className := s"promotion-choice $colorClass",
            onClick --> { _ =>
              pendingPromotionVar.set(None)
              postMove(s"${p.from} ${p.to}=$key")
            },
            pieceSvg(name)
          )
        }

  private def loadModal(): HtmlElement =
    div(
      className <-- loadOpenVar.signal.map(o =>
        if o then "modal visible" else "modal"
      ),
      onClick --> { _ => loadOpenVar.set(false) },
      div(
        className := "modal-dialog load-dialog",
        onClick.stopPropagation --> { _ => () },
        paperLayer(),
        h2("Load Game"),
        p(
          "Paste FEN, PGN, or JSON — the format is auto-detected."
        ),
        textArea(
          className := "load-input",
          rows := 10,
          placeholder := "rnbqkbnr/pppppppp/8/...  or  1. e4 e5 2. Nf3 ...",
          spellCheck := false,
          controlled(
            value <-- loadInputVar.signal,
            onInput.mapToValue --> loadInputVar
          )
        ),
        div(
          className := "modal-actions",
          button(
            className := "secondary-btn",
            onClick --> { _ =>
              val raw = loadInputVar.now().trim
              if raw.nonEmpty then
                postLoad(raw)
                loadInputVar.set("")
                loadOpenVar.set(false)
            },
            "Load"
          ),
          button(
            className := "secondary-btn",
            onClick --> { _ => loadOpenVar.set(false) },
            "Cancel"
          )
        )
      )
    )

  private def confirmModal(): HtmlElement =
    div(
      className <-- confirmVar.signal.map(c =>
        if c.isDefined then "modal visible" else "modal"
      ),
      onClick --> { _ => confirmVar.set(None) },
      div(
        className := "modal-dialog confirm-dialog",
        onClick.stopPropagation --> { _ => () },
        paperLayer(),
        h2(child.text <-- confirmVar.signal.map(_.map(_.title).getOrElse(""))),
        p(child.text <-- confirmVar.signal.map(_.map(_.message).getOrElse(""))),
        div(
          className := "modal-actions",
          button(
            className := "secondary-btn",
            onClick --> { _ => confirmVar.set(None) },
            "Cancel"
          ),
          button(
            className <-- confirmVar.signal.map(c =>
              if c.exists(_.destructive) then "quit-btn" else "secondary-btn"
            ),
            onClick --> { _ =>
              confirmVar.now().foreach(_.action())
              confirmVar.set(None)
            },
            child.text <-- confirmVar.signal.map(
              _.map(_.confirmLabel).getOrElse("Confirm")
            )
          )
        )
      )
    )

  private def exportModal(): HtmlElement =
    div(
      className <-- exportVar.signal.map(o =>
        if o.isDefined then "modal visible" else "modal"
      ),
      onClick --> { _ => exportVar.set(None) },
      div(
        className := "modal-dialog export-dialog",
        onClick.stopPropagation --> { _ => () },
        paperLayer(),
        h2(
          child.text <-- exportVar.signal.map {
            case Some(r) => s"Export (${r.format.toUpperCase})"
            case None    => "Export"
          }
        ),
        textArea(
          className := "export-output",
          rows := 10,
          readOnly := true,
          value <-- exportVar.signal.map(_.map(_.content).getOrElse(""))
        ),
        div(
          className := "modal-actions",
          button(
            className := "secondary-btn",
            onClick --> { _ =>
              exportVar.now().foreach { r =>
                copyToClipboard(r.content)
                showToast(s"Copied ${r.format.toUpperCase} to clipboard")
              }
            },
            "Copy"
          ),
          button(
            className := "secondary-btn",
            onClick --> { _ => exportVar.set(None) },
            "Close"
          )
        )
      )
    )

  // --------------------------------------------------------------------------
  // HTTP + SSE
  // --------------------------------------------------------------------------

  private val backend = FetchBackend()

  private val getStateClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.getState,
      None,
      backend
    )
  private val postMoveClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postMove,
      None,
      backend
    )
  private val postUndoClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postUndo,
      None,
      backend
    )
  private val postRedoClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postRedo,
      None,
      backend
    )
  private val postDrawClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postDraw,
      None,
      backend
    )
  private val postNewClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postNew,
      None,
      backend
    )
  private val postQuitClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postQuit,
      None,
      backend
    )
  private val postLoadClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postLoad,
      None,
      backend
    )
  private val postForfeitClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postForfeit,
      None,
      backend
    )

  private def fetchState(): Unit =
    getStateClient(None).foreach(handleStateResult)

  private def connectEvents(): Unit =
    val source = new dom.EventSource("/api/events")
    source.addEventListener(
      "state",
      (e: dom.MessageEvent) =>
        e.data.asInstanceOf[String].fromJson[BoardStateDto] match
          case Right(state) => stateVar.set(Some(state))
          case Left(err)    => showToast(s"Bad state payload: $err")
    )
    source.addEventListener(
      "quit",
      (_: dom.MessageEvent) =>
        source.close()
        goodbyeVar.set(true)
    )

  private def handleStateResult(
      result: Either[ErrorDto, StateResponse]
  ): Unit =
    result match
      case Right(StateResponse.View(state)) => stateVar.set(Some(state))
      case Right(_: StateResponse.Export)   => ()
      case Left(err)                        => showToast(err.error)

  private def postMove(move: String): Unit =
    postMoveClient(MoveRequest(move)).foreach {
      case Right(_)  => ()
      case Left(err) => showToast(err.error)
    }

  private def postUndo(): Unit = postAndToastErrors(postUndoClient(()))
  private def postRedo(): Unit = postAndToastErrors(postRedoClient(()))
  private def postDraw(): Unit = postAndToastErrors(postDrawClient(()))
  private def postNew(): Unit = postAndToastErrors(postNewClient(()))
  private def postForfeit(): Unit = postAndToastErrors(postForfeitClient(()))
  private def postQuit(): Unit = postQuitClient(()).foreach(_ => ())

  private def postLoad(raw: String): Unit =
    postLoadClient(LoadRequest(raw)).foreach {
      case Right(_)  => ()
      case Left(err) => showToast(err.error)
    }

  private def doExport(format: String): Unit =
    getStateClient(Some(format)).foreach {
      case Right(StateResponse.Export(resp)) => exportVar.set(Some(resp))
      case Right(_: StateResponse.View)      => ()
      case Left(err)                         => showToast(err.error)
    }

  private def postAndToastErrors(
      f: Future[Either[ErrorDto, BoardStateDto]]
  ): Unit =
    f.foreach {
      case Right(_)  => ()
      case Left(err) => showToast(err.error)
    }

  private def showToast(msg: String): Unit =
    toastVar.set(Some(msg))
    dom.window.setTimeout(() => toastVar.set(None), 3000)

  private def copyToClipboard(text: String): Unit =
    val nav = dom.window.navigator.asInstanceOf[js.Dynamic]
    nav.clipboard.writeText(text)

  // --------------------------------------------------------------------------
  // Drag + drop
  // --------------------------------------------------------------------------

  private def handleDragStart(pos: String, e: dom.DragEvent): Unit =
    dragSourceVar.set(Some(pos))
    e.dataTransfer.effectAllowed =
      "move".asInstanceOf[dom.DataTransferEffectAllowedKind]
    e.dataTransfer.setData("text/plain", "")
    // Replace the browser's default drag image. The default is a screenshot
    // of the dragged element at its on-screen position, which means
    // transparent SVG pixels reveal the source square's colour underneath
    // — looks like a coloured rectangle around the piece. Cloning the
    // piece into the body (no parent square behind it) gives a clean
    // transparent capture.
    //
    // Positioning detail: the clone has to be RENDERED for the browser to
    // capture it. Browsers (Chrome especially) won't capture an element
    // positioned far off-screen via top: -9999px — they treat it as
    // not-rendered. Position it at (0, 0) with opacity: 0 instead — laid
    // out and rendered, just visually invisible until the browser
    // snapshots and removes it on the next tick.
    val src = e.currentTarget.asInstanceOf[dom.html.Element]
    val ghost = src.cloneNode(true).asInstanceOf[dom.html.Element]
    val rect = src.getBoundingClientRect()
    ghost.style.position = "absolute"
    ghost.style.top = "0"
    ghost.style.left = "0"
    ghost.style.width = s"${rect.width}px"
    ghost.style.height = s"${rect.height}px"
    ghost.style.opacity = "0"
    ghost.style.pointerEvents = "none"
    ghost.style.zIndex = "9999"
    dom.document.body.appendChild(ghost)
    e.dataTransfer.setDragImage(
      ghost,
      (rect.width / 2).toInt,
      (rect.height / 2).toInt
    )
    // Remove the ghost on the next tick — the browser has captured the
    // drag image by then, but the node still needs to leave the DOM.
    dom.window.setTimeout(() => dom.document.body.removeChild(ghost), 0)

  private def handleDrop(target: String, state: BoardStateDto): Unit =
    dragSourceVar.now() match
      case None                       => ()
      case Some(src) if src == target => dragSourceVar.set(None)
      case Some(src) =>
        if isPawnPromotion(src, target, state) then
          pendingPromotionVar.set(Some(PendingPromotion(src, target)))
        else postMove(s"$src $target")
        dragSourceVar.set(None)

  private def isPawnPromotion(
      from: String,
      to: String,
      state: BoardStateDto
  ): Boolean =
    Logic.isPawnPromotion(from, to, state)

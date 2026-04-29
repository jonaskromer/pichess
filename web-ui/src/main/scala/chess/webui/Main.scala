package chess.webui

import chess.api.{BoardStateDto, Endpoints, ErrorDto, ExportResponse, GameStatusDto, LoadRequest, MoveEntryDto, MoveRequest, SquareDto, StateResponse}
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
      action: () => Unit,
  )
  private val confirmVar: Var[Option[ConfirmRequest]] = Var(None)
  private def askConfirm(req: ConfirmRequest): Unit = confirmVar.set(Some(req))

  // --------------------------------------------------------------------------
  // Top-level component
  // --------------------------------------------------------------------------

  private def App(): HtmlElement =
    div(
      onMountCallback { _ =>
        fetchState()
        connectEvents()
        syncHelpFromHash()
        dom.window.addEventListener(
          "hashchange",
          (_: dom.Event) => syncHelpFromHash(),
        )
      },
      child <-- goodbyeVar.signal.map {
        case true  => goodbyeScreen()
        case false => mainUi()
      },
    )

  private def syncHelpFromHash(): Unit =
    helpOpenVar.set(dom.window.location.hash == "#help")

  private def goodbyeScreen(): HtmlElement =
    div(
      styleAttr := "display:flex;justify-content:center;align-items:center;" +
        "height:100vh;font-size:24px;color:#f7a072;",
      "Goodbye!",
    )

  private def mainUi(): HtmlElement =
    div(
      className := "app-shell",
      header(),
      child <-- helpOpenVar.signal.map {
        case true  => HelpView.render()
        case false => gameBody()
      },
      promotionOverlay(),
      loadModal(),
      exportModal(),
      confirmModal(),
      toastElement(),
    )

  private def gameBody(): HtmlElement =
    div(
      className := "app",
      boardArea(),
      sidebar(),
    )

  // --------------------------------------------------------------------------
  // Header
  // --------------------------------------------------------------------------

  private def header(): HtmlElement =
    headerTag(
      className := "header",
      div(
        className := "header-brand",
        span(className := "header-logo", "🍑"),
        span(child.text <-- helpOpenVar.signal.map(o =>
          if o then "piChess Help" else "piChess"
        )),
      ),
      div(className := "header-spacer"),
      div(
        className := "header-actions",
        children <-- helpOpenVar.signal.map(o =>
          if o then helpHeaderActions else gameHeaderActions
        ),
      ),
    )

  private def gameHeaderActions: List[HtmlElement] = List(
    button(
      className := "header-new-btn",
      onClick --> { _ => askConfirm(confirmNewGame) },
      "New Game",
    ),
    a(
      className := "header-link",
      href      := "#help",
      "Help",
    ),
    a(
      className := "header-link",
      href      := "/docs",
      target    := "_blank",
      rel       := "noopener noreferrer",
      "Docs ↗",
    ),
  )

  private def helpHeaderActions: List[HtmlElement] = List(
    a(
      className := "header-link",
      href      := "#",
      "← Game",
    ),
    a(
      className := "header-link",
      href      := "/docs",
      target    := "_blank",
      rel       := "noopener noreferrer",
      "Docs ↗",
    ),
  )

  // --------------------------------------------------------------------------
  // Board
  // --------------------------------------------------------------------------

  private def boardArea(): HtmlElement =
    div(
      className := "board-area",
      capturedTray(topSide = true),
      div(
        className := "board-wrapper",
        rankLabels(),
        board(),
        fileLabels(),
      ),
      capturedTray(topSide = false),
    )

  private def capturedTray(topSide: Boolean): HtmlElement =
    // (color shown in this tray, list of piece-type names lost). White's lost
    // pieces sit above the board by default (where black "stacks" them);
    // flip swaps both the tray side and the colour rendered.
    val signal = stateVar.signal.combineWith(flippedVar.signal).map {
      case (None, _) => ("white", List.empty[String])
      case (Some(s), flipped) =>
        val (whiteLost, blackLost) = Logic.capturedFromSquares(s.squares)
        val showsWhite = if flipped then !topSide else topSide
        if showsWhite then ("white", whiteLost) else ("black", blackLost)
    }
    div(
      // visibility:hidden when empty (via .tray-empty) so layout space is
      // preserved — board doesn't jump up/down as captures appear.
      className <-- signal.map { case (_, p) =>
        if p.isEmpty then "captured-tray tray-empty" else "captured-tray"
      },
      children <-- signal.map { case (color, ps) =>
        ps.map(renderCapturedPiece(_, color))
      },
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
      },
    )

  private def fileLabels(): HtmlElement =
    div(
      className := "file-labels",
      children <-- flippedVar.signal.map { flipped =>
        val files =
          if flipped then ('a' to 'h').toList.reverse
          else ('a' to 'h').toList
        files.map(c => div(c.toString))
      },
    )

  private def board(): HtmlElement =
    div(
      className := "board",
      children <-- stateVar.signal.combineWith(flippedVar.signal).map {
        case (None, _) => List.empty
        case (Some(s), flipped) =>
          val squares = if flipped then s.squares.reverse else s.squares
          squares.map(renderSquare(s, _))
      },
    )

  private def renderSquare(
      state: BoardStateDto,
      sq: SquareDto,
  ): HtmlElement =
    val isChecked = state.checkedKingPos.contains(sq.pos)
    val squareClasses =
      s"square ${sq.squareColor}" + (if isChecked then " in-check" else "")
    div(
      className := squareClasses,
      dataAttr("pos") := sq.pos,
      onDragOver.preventDefault --> { _ => () },
      onDrop.preventDefault --> { _ => handleDrop(sq.pos, state) },
      sq.piece.map { name =>
        span(
          className := s"piece ${sq.pieceColor.getOrElse("")}-piece",
          draggable := true,
          onDragStart --> { e => handleDragStart(sq.pos, e) },
          onDragEnd --> { _ => dragSourceVar.set(None) },
          pieceSvg(name),
        )
      },
    )

  /** Render a chess piece as an inline SVG that pulls geometry from the
    * shared sprite at `/web/pieces/<name>.svg#<name>`. Inline SVG (rather
    * than `<img>`) is needed so the parent's `--piece-primary` and
    * `--piece-secondary` CSS variables cascade through the `<use>`
    * reference into the symbol's paths.
    *
    * The host SVG's viewBox matches each piece's source aspect ratio so the
    * browser can derive an intrinsic height when the surrounding CSS sets
    * `width: <fixed>; height: auto`. All pieces then share a common base
    * width while keeping their natural relative heights — kings tall,
    * pawns short.
    */
  private def pieceSvg(name: String): SvgElement =
    svg.svg(
      svg.viewBox := pieceViewBox(name),
      svg.cls     := "piece-svg",
      svg.use(svg.href := s"/web/pieces/$name.svg#$name"),
    )

  // Source-coordinate viewBox per piece — copied from each unified SVG
  // file so the host SVG inherits its aspect ratio.
  private val pieceViewBox: Map[String, String] = Map(
    "pawn"   -> "0 0 460.1 624.7",
    "rook"   -> "0 0 498.5 747.5",
    "knight" -> "0 0 507.7 777.5",
    "bishop" -> "0 0 460.1 856.8",
    "queen"  -> "0 0 544.3 1000.7",
    "king"   -> "0 0 590.5 1259.6",
  )

  // --------------------------------------------------------------------------
  // Sidebar
  // --------------------------------------------------------------------------

  private def sidebar(): HtmlElement =
    div(
      className := "sidebar",
      statusIndicator(),
      moveLogContainer(),
      controls(),
    )

  private def statusIndicator(): HtmlElement =
    div(
      child <-- stateVar.signal.map {
        case None    => emptyNode
        case Some(s) =>
          s.status.kind match
            case "checkmate"   => checkmateBanner(s.status)
            case "draw"        => drawBanner(s.status)
            case "resignation" => resignationBanner(s.status)
            case _             => turnIndicator(s)
      },
    )

  private def turnIndicator(s: BoardStateDto): HtmlElement =
    val name = if s.activeColor == "white" then "White" else "Black"
    div(
      className := "turn-indicator",
      div(className := s"turn-dot ${s.activeColor}"),
      span(s"$name to move"),
    )

  private def checkmateBanner(status: GameStatusDto): HtmlElement =
    val winner = status.winner.map(capitalize).getOrElse("Someone")
    div(className := "banner win", s"$winner wins by checkmate")

  private def drawBanner(status: GameStatusDto): HtmlElement =
    val reason = status.reason.map(Logic.humanizeDrawReason).getOrElse("agreement")
    div(className := "banner draw", s"Draw — $reason")

  private def resignationBanner(status: GameStatusDto): HtmlElement =
    val winner = status.winner.map(capitalize).getOrElse("Someone")
    div(className := "banner win", s"$winner wins by resignation")

  private def capitalize(s: String): String =
    if s.isEmpty then s else s.head.toUpper + s.tail

  private def moveLogContainer(): HtmlElement =
    div(
      className := "move-log-container",
      h2(className := "section-title", "Moves"),
      div(
        className := "move-log",
        children <-- stateVar.signal.map {
          case None    => List.empty
          case Some(s) => renderMoveLog(s.moveLog)
        },
      ),
    )

  private def renderMoveLog(moves: List[MoveEntryDto]): List[HtmlElement] =
    if moves.isEmpty then List(div(className := "move-log-empty", "No moves yet"))
    else
      Logic.groupMovesByTwo(moves).map { case (num, white, blackOpt) =>
        val cells = List(
          span(className := "move-number", s"$num."),
          span(className := "move-san", white.san),
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
          tpe          := "text",
          idAttr       := "moveInput",
          placeholder  := "e.g. e2e4 or Nf3",
          autoComplete := "off",
          spellCheck   := false,
          controlled(
            value <-- moveInputVar.signal,
            onInput.mapToValue --> moveInputVar,
          ),
        ),
        button(tpe := "submit", "Move"),
      ),
      sectionLabel("History"),
      div(
        className := "btn-row",
        secondaryButton("Undo", () => postUndo()),
        secondaryButton("Redo", () => postRedo()),
        secondaryButton("Draw", () => askConfirm(confirmDraw)),
      ),
      sectionLabel("Board"),
      div(
        className := "btn-row",
        button(
          className := "secondary-btn",
          onClick --> { _ => flippedVar.update(!_) },
          child.text <-- flippedVar.signal.map(f =>
            if f then "Unflip" else "Flip"
          ),
        ),
      ),
      sectionLabel("Data"),
      div(
        className := "btn-row",
        secondaryButton("Load", () => loadOpenVar.set(true)),
        secondaryButton("FEN",  () => doExport("fen")),
        secondaryButton("PGN",  () => doExport("pgn")),
        secondaryButton("JSON", () => doExport("json")),
      ),
      sectionLabel("Game"),
      div(
        className := "btn-row",
        button(
          className := "secondary-btn",
          onClick --> { _ => askConfirm(confirmForfeit) },
          "Forfeit",
        ),
        button(
          className := "quit-btn",
          onClick --> { _ => askConfirm(confirmQuit) },
          "Quit",
        ),
      ),
    )

  private val confirmNewGame: ConfirmRequest = ConfirmRequest(
    title        = "Start a new game?",
    message      = "This discards the current game and resets to the starting position.",
    confirmLabel = "New Game",
    destructive  = true,
    action       = () => postNew(),
  )

  private val confirmDraw: ConfirmRequest = ConfirmRequest(
    title        = "Claim a draw?",
    message      = "Only valid if the 50-move rule applies or the position has occurred at least three times.",
    confirmLabel = "Claim Draw",
    destructive  = false,
    action       = () => postDraw(),
  )

  private val confirmQuit: ConfirmRequest = ConfirmRequest(
    title        = "Shut down the server?",
    message      = "This stops piChess and closes the page. Make sure to save anything you want to keep.",
    confirmLabel = "Quit",
    destructive  = true,
    action       = () => postQuit(),
  )

  private val confirmForfeit: ConfirmRequest = ConfirmRequest(
    title        = "Forfeit the game?",
    message      = "The current player resigns; the opponent wins.",
    confirmLabel = "Forfeit",
    destructive  = true,
    action       = () => postForfeit(),
  )

  private def sectionLabel(text: String): HtmlElement =
    div(className := "section-title", text)

  private def secondaryButton(label: String, onClickAction: () => Unit): HtmlElement =
    button(
      className := "secondary-btn",
      onClick --> { _ => onClickAction() },
      label,
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
      child.text <-- toastVar.signal.map(_.getOrElse("")),
    )

  private def promotionOverlay(): HtmlElement =
    div(
      idAttr := "promotionOverlay",
      className <-- pendingPromotionVar.signal.map(p =>
        if p.isDefined then "promotion-overlay visible" else "promotion-overlay"
      ),
      div(
        idAttr    := "promotionDialog",
        className := "promotion-dialog",
        children <-- pendingPromotionVar.signal
          .combineWith(stateVar.signal)
          .map {
            case (Some(p), Some(s)) => promotionChoices(p, s)
            case _                  => List.empty
          },
      ),
    )

  private def promotionChoices(
      p: PendingPromotion,
      state: BoardStateDto,
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
            pieceSvg(name),
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
        h2("Load Game"),
        p(
          "Paste FEN, PGN, or JSON — the format is auto-detected."
        ),
        textArea(
          className   := "load-input",
          rows        := 10,
          placeholder := "rnbqkbnr/pppppppp/8/...  or  1. e4 e5 2. Nf3 ...",
          spellCheck  := false,
          controlled(
            value <-- loadInputVar.signal,
            onInput.mapToValue --> loadInputVar,
          ),
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
            "Load",
          ),
          button(
            className := "secondary-btn",
            onClick --> { _ => loadOpenVar.set(false) },
            "Cancel",
          ),
        ),
      ),
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
        h2(child.text <-- confirmVar.signal.map(_.map(_.title).getOrElse(""))),
        p(child.text <-- confirmVar.signal.map(_.map(_.message).getOrElse(""))),
        div(
          className := "modal-actions",
          button(
            className := "secondary-btn",
            onClick --> { _ => confirmVar.set(None) },
            "Cancel",
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
            ),
          ),
        ),
      ),
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
        h2(
          child.text <-- exportVar.signal.map {
            case Some(r) => s"Export (${r.format.toUpperCase})"
            case None    => "Export"
          }
        ),
        textArea(
          className := "export-output",
          rows      := 10,
          readOnly  := true,
          value <-- exportVar.signal.map(_.map(_.content).getOrElse("")),
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
            "Copy",
          ),
          button(
            className := "secondary-btn",
            onClick --> { _ => exportVar.set(None) },
            "Close",
          ),
        ),
      ),
    )

  // --------------------------------------------------------------------------
  // HTTP + SSE
  // --------------------------------------------------------------------------

  private val backend = FetchBackend()

  private val getStateClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.getState, None, backend)
  private val postMoveClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postMove, None, backend)
  private val postUndoClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postUndo, None, backend)
  private val postRedoClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postRedo, None, backend)
  private val postDrawClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postDraw, None, backend)
  private val postNewClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postNew, None, backend)
  private val postQuitClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postQuit, None, backend)
  private val postLoadClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postLoad, None, backend)
  private val postForfeitClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(Endpoints.postForfeit, None, backend)

  private def fetchState(): Unit =
    getStateClient(None).foreach(handleStateResult)

  private def connectEvents(): Unit =
    val source = new dom.EventSource("/api/events")
    source.addEventListener(
      "state",
      (e: dom.MessageEvent) =>
        e.data.asInstanceOf[String].fromJson[BoardStateDto] match
          case Right(state) => stateVar.set(Some(state))
          case Left(err)    => showToast(s"Bad state payload: $err"),
    )
    source.addEventListener(
      "quit",
      (_: dom.MessageEvent) =>
        source.close()
        goodbyeVar.set(true),
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

  private def postUndo(): Unit    = postAndToastErrors(postUndoClient(()))
  private def postRedo(): Unit    = postAndToastErrors(postRedoClient(()))
  private def postDraw(): Unit    = postAndToastErrors(postDrawClient(()))
  private def postNew(): Unit     = postAndToastErrors(postNewClient(()))
  private def postForfeit(): Unit = postAndToastErrors(postForfeitClient(()))
  private def postQuit(): Unit    = postQuitClient(()).foreach(_ => ())

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
      state: BoardStateDto,
  ): Boolean =
    Logic.isPawnPromotion(from, to, state)

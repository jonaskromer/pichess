package chess.webui

import chess.api.{
  BoardStateDto,
  CreateGameRequest,
  Endpoints,
  ErrorDto,
  ExportResponse,
  GameSnapshot,
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

  /** Pointer-event drag state. Replaces the previous HTML5-drag setup
    * (which had unreliable `setDragImage` snapshots for inline SVG with
    * cross-document `<use>` references). The data captured here drives
    *   1. the `is-being-dragged` class on the source piece (hides it),
    *   2. the floating-clone's transform (follows the cursor),
    *   3. the `body.is-dragging` class (cursor: grabbing).
    * `moved` distinguishes a drag-and-drop from a stationary click —
    * pointerup short-circuits if the user never crossed the 4 px
    * threshold, so a tap on a piece doesn't accidentally try to "move"
    * it onto itself.
    */
  private case class DragState(
      fromPos: String,
      pieceName: String,
      pieceColorClass: String,
      pointerId: Double,
      offsetX: Double,
      offsetY: Double,
      cursorX: Double,
      cursorY: Double,
      moved: Boolean,
  )
  private val dragVar: Var[Option[DragState]] = Var(None)

  /** One signal shared by all 64 squares. With `.distinct`, the value
    * only emits when the dragged-from square actually changes — twice per
    * drag (start, end). Per-square `cls.toggle` then subscribes to a
    * cheap equality check on a signal that itself emits ~twice per drag,
    * instead of recomputing 64 predicates on every pointermove.
    */
  private val dragFromPosSignal: Signal[Option[String]] =
    dragVar.signal.map(_.map(_.fromPos)).distinct

  // Hit-testing reads `.board` via `querySelector` at pointerup time
  // (not via a stashed Var). The Var-based approach raced with
  // mount/unmount callbacks during reactive child swaps and could leave
  // us holding a reference to a detached element; `querySelector`
  // always returns the currently-attached `.board`. The lookup runs
  // once per drop, not per frame, so cost is negligible.

  // Coalesces 240 Hz+ pointermove events down to display rate. The handler
  // stores the latest cursor coords and queues a single rAF; everything
  // else (`dragVar` update, floating-clone transform, body class) runs
  // inside the frame.
  private var latestPointerX: Double = 0.0
  private var latestPointerY: Double = 0.0
  private var rafQueued: Boolean = false

  private val pendingPromotionVar: Var[Option[PendingPromotion]] = Var(None)
  private val toastVar: Var[Option[String]] = Var(None)
  private val flippedVar: Var[Boolean] = Var(false)
  /** Per-tab session id, generated on first load and persisted in
    * localStorage. Sent as `X-Session-Id` on every mutating request so the
    * gateway can refuse moves from sessions that aren't an active player.
    * For local games this id is registered as the sole permitted mover
    * when the game is created.
    */
  private val sessionId: String =
    Option(dom.window.localStorage.getItem("pichess.sessionId"))
      .filter(_.nonEmpty)
      .getOrElse {
        val fresh = java.util.UUID.randomUUID().toString
        dom.window.localStorage.setItem("pichess.sessionId", fresh)
        fresh
      }
  /** Current game id. With multi-game routing the gateway no longer tracks
    * a single "active" game — the client owns this. We mint a fresh game
    * on first load, capture its id here, then thread it through every
    * subsequent HTTP call and the SSE subscription URL.
    */
  private val gameIdVar: Var[Option[String]] = Var(None)
  private val loadOpenVar: Var[Boolean] = Var(false)
  private val loadInputVar: Var[String] = Var("")
  private val exportVar: Var[Option[ExportResponse]] = Var(None)
  // Help is rendered as an in-SPA view — not a separate route — so that the
  // browser back button returns to the game without a full page reload.
  // We sync this var with `location.hash` so deep-links (#help) and the back
  // button just work via hashchange events.
  private val helpOpenVar: Var[Boolean] = Var(false)
  // Move input is promoted to object scope so the `moveLogContainer`
  // function can render the form alongside the scrolling history while
  // the rest of the controls live in the sidebar post-its.
  private val moveInputVar: Var[String] = Var("")

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
    // Flip the class on <html>; the paper SVGs are pre-rasterised in
    // both themes (see HtmlPage's paperSprites) so the only cost of a
    // toggle is a CSS `display` swap on already-painted symbols. No
    // filter re-computation, no view-transition snapshot needed.
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
      onMountCallback { ctx =>
        // The HtmlPage bootstrap script already set the dark class before
        // first paint; this call is the safety net for the (rare) case
        // where the script ran before localStorage was readable.
        applyTheme(themeVar.now())
        // Mint a fresh game on first load, then pull state + subscribe.
        // The id flows through gameIdVar so HTTP calls and SSE both pick
        // it up reactively.
        bootstrapGame()
        syncHelpFromHash()
        dom.window.addEventListener(
          "hashchange",
          (_: dom.Event) => syncHelpFromHash()
        )
        // Document-level pointer listeners drive the drag's middle and
        // end. We don't rely on setPointerCapture — when the source
        // piece gets `is-being-dragged`, some engines implicitly drop
        // capture, after which pointermove/up only reach the cursor's
        // current target (often a bare `.square`). Listening on the
        // document is unconditional. Each handler checks dragVar.now()
        // and is a no-op when no drag is in flight.
        dom.document.addEventListener(
          "pointermove",
          ((e: dom.Event) =>
            handleGlobalPointerMove(e.asInstanceOf[dom.PointerEvent])
          ): js.Function1[dom.Event, Unit],
        )
        dom.document.addEventListener(
          "pointerup",
          ((e: dom.Event) =>
            handleGlobalPointerUp(e.asInstanceOf[dom.PointerEvent])
          ): js.Function1[dom.Event, Unit],
        )
        dom.document.addEventListener(
          "pointercancel",
          ((_: dom.Event) => {
            rafQueued = false
            latestPointerX = 0.0
            latestPointerY = 0.0
            dragVar.set(None)
          }): js.Function1[dom.Event, Unit],
        )
        // Flip a `body.is-dragging` class while a drag is in flight so
        // the cursor turns to `grabbing` everywhere on the page (the
        // CSS rule lives in style.css). `.distinct` so it only fires
        // when the boolean actually changes, not on every pointermove.
        // Owned by the App element so it unsubscribes if App unmounts.
        dragVar.signal
          .map(_.isDefined)
          .distinct
          .foreach { active =>
            val cl = dom.document.body.classList
            if active then cl.add("is-dragging") else cl.remove("is-dragging")
          }(using ctx.owner)
      },
      mainUi()
    )

  private def syncHelpFromHash(): Unit =
    helpOpenVar.set(dom.window.location.hash == "#help")

  private def mainUi(): HtmlElement =
    div(
      className := "app-shell",
      pageBackground(),
      header(),
      // `.distinct` — see the rationale on the App-level signal above.
      child <-- helpOpenVar.signal.distinct.map {
        case true  => HelpView.render()
        case false => gameBody()
      },
      promotionOverlay(),
      loadModal(),
      exportModal(),
      confirmModal(),
      toastElement(),
      // Floating clone of the dragged piece. Mounted ONCE — the inner
      // piece span is the only thing that re-renders, and only when the
      // piece identity changes (drag start / drag end). The transform
      // attribute updates every pointermove, but updating an attribute
      // on a stable element is a sub-frame compositor flip rather than
      // a DOM tear-down + rebuild. Earlier `child.maybe <-- signal.map(
      // _.map(floatingDragLayer))` re-built the entire floating layer
      // on every pointermove — that was the source of the visible
      // flicker AND the lag.
      floatingDragLayer,
      // Cancel an in-flight drag when the user presses Escape. Filtering
      // by `dragVar.now().isDefined` keeps the listener no-op when no
      // drag is active so it doesn't compete with anything else.
      documentEvents(_.onKeyDown)
        .filter(e => e.key == "Escape" && dragVar.now().isDefined)
        --> { _ => dragVar.set(None) },
    )

  /** Stable floating layer. Mounted once for the App's lifetime AND with
    * all six piece SVGs pre-mounted as siblings inside it. This is the
    * Safari-specific shape:
    *
    *   - Chrome aggressively caches the resolved-shadow tree of inline
    *     `<use href="#symbol"/>` and re-uses it across mount/unmount of
    *     the consuming `<svg>` element. Mount on drag start, unmount on
    *     drag end is essentially free.
    *   - Safari does NOT share that work across contexts. Each fresh
    *     `<svg><use href="#pawn"/></svg>` mounted inside the floating
    *     layer triggers a re-resolve, and the resolve only kicks off
    *     once the element actually paints. With the previous "mount the
    *     inner span on drag start" shape, that re-resolve was on the
    *     critical path of pointerdown → first paint. Pre-mounting all
    *     six pieces here pays the cost once at app boot (when nothing
    *     visible is changing) and reduces drag start to a single attribute
    *     flip — `data-active-piece` — which CSS uses to display exactly
    *     one of the six children.
    *
    * The transform is updated by direct `el.style.transform = …` writes
    * inside the rAF callback so we skip Laminar's `styleAttr` reactive
    * binding and the `setAttribute("style", …)` parse step at 60-120 Hz
    * pointermove rates. The colour-cascade class (`white-piece` /
    * `black-piece`) is on the inner `.piece` span and updates twice per
    * drag via `className <--`; cheap.
    *
    * Idle state parks the layer at translate(-9999px, -9999px) instead
    * of toggling visibility — keeps the GPU compositor layer warm in
    * Safari (visibility flips destroy and recreate the layer in some
    * versions). The layer is invisible to the user either way because
    * it's far off-screen and `pointer-events: none`.
    */
  private val floatingDragLayer: HtmlElement =
    div(
      className := "drag-floating",
      onMountCallback { ctx =>
        val el = ctx.thisNode.ref.asInstanceOf[dom.html.Element]
        // Park off-screen synchronously at mount, before the first
        // paint, so users never see the pre-mounted pieces.
        el.style.transform = "translate(-9999px, -9999px)"
        dragVar.signal.foreach {
          case Some(s) =>
            el.style.transform =
              s"translate(${s.cursorX - s.offsetX}px, ${s.cursorY - s.offsetY}px)"
          case None =>
            el.style.transform = "translate(-9999px, -9999px)"
        }(using ctx.owner)
      },
      span(
        // The inner `.piece` span carries the colour-cascade class so
        // CSS variables `--piece-primary` / `--piece-secondary` flow
        // into whichever pre-mounted SVG is currently shown. When no
        // drag is in flight the layer is parked off-screen anyway, so
        // the default class ("piece" with no colour modifier) is fine.
        className <-- dragVar.signal.map { s =>
          s.fold("piece")(d => s"piece ${d.pieceColorClass}")
        },
        // Drives which one of the pre-mounted SVGs is `display: block`
        // (CSS attribute selector in style.css). Empty string when no
        // drag — none match, all stay `display: none`.
        dataAttr("active-piece") <-- dragVar.signal.map(_.fold("")(_.pieceName)),
        Seq("pawn", "rook", "knight", "bishop", "queen", "king").map { name =>
          svg.svg(
            svg.viewBox := pieceViewBox(name),
            svg.cls := s"piece-svg piece-svg-$name",
            svg.use(svg.href := s"#$name")
          )
        }
      )
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
    // New Game lives inside the Move post-it now, not the header — keeps
    // the header for navigation only and groups the destructive "reset
    // game state" action visually with the "make a move" input it
    // resets.
    themeToggleButton(),
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
      sq.piece.map { name =>
        span(
          className := s"piece ${sq.pieceColor.getOrElse("")}-piece",
          // The is-being-dragged class hides the source piece during
          // drag (opacity:0 in CSS) so the only piece the user sees is
          // the floating clone following the cursor. We bind to the
          // shared `dragFromPosSignal` (already `.distinct` — emits
          // twice per drag) so all 64 squares share one upstream
          // computation instead of running their own predicate on
          // every pointermove.
          cls("is-being-dragged") <-- dragFromPosSignal
            .map(_.contains(sq.pos))
            .distinct,
          // Only `pointerdown` is wired to the piece. `pointermove`,
          // `pointerup`, and `pointercancel` are attached at the document
          // level (see App's onMountCallback) — relying on
          // `setPointerCapture` was unreliable, because the
          // `is-being-dragged` class can implicitly release capture in
          // some engines, after which subsequent events fire on whatever
          // is under the cursor (frequently a `.square` element with no
          // piece-level handler). Document-level listeners fire
          // regardless of cursor position and check `dragVar.now()` to
          // decide whether they're dealing with an in-flight drag.
          onPointerDown --> { e =>
            handlePointerDown(sq.pos, name, sq.pieceColor.getOrElse(""), e)
          },
          pieceSvg(name),
        )
      },
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
    // Document-internal <use> — the piece <symbol id="<name>"> is
    // inlined into the page's .svg-sprite-host (see HtmlPage.scala).
    // Same-document references resolve synchronously with no fetch,
    // which is what makes the floating drag clone appear instantly on
    // pointerdown. Cross-document `<use href="external.svg#id"/>` still
    // worked but introduced a per-element resolve step the first time
    // the symbol was rendered in a new container.
    svg.svg(
      svg.viewBox := pieceViewBox(name),
      svg.cls := "piece-svg",
      svg.use(svg.href := s"#$name")
    )

  // Source-coordinate viewBox per piece — copied from each unified SVG
  // file so the host SVG inherits its aspect ratio. `lazy val` because
  // `floatingDragLayer` (declared above) eagerly calls `pieceViewBox(...)`
  // for all six pieces during its own `val` init, before this map is
  // reached in source order. Lazy initialisation defers the map until
  // first access, so reverse-order dependencies between object members
  // resolve cleanly.
  private lazy val pieceViewBox: Map[String, String] = Map(
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
    if s.isEmpty then s else s"${s.head.toUpper}${s.tail}"

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
        ),
        // Move input lives BELOW the scrolling log, on the same paper
        // but outside the OS-managed scroll viewport — putting it inside
        // the scroll container would scroll it out of reach. Pairing it
        // with the log keeps the visual relationship "history above,
        // next move below" intact and frees the sidebar from a tiny
        // dedicated post-it whose only resident was this one input.
        form(
          idAttr := "moveForm",
          onSubmit.preventDefault --> { _ =>
            val v = moveInputVar.now().trim
            if v.nonEmpty then
              postMove(v)
              moveInputVar.set("")
          },
          // The wrapper carries the hand-drawn underline pseudo so the
          // input itself can stay borderless. Also lets the underline
          // span the input width without the input's own box model
          // pushing the line around.
          span(
            className := "move-input-wrap",
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
            )
          ),
          // Icon-only submit — matches the visual vocabulary of Undo /
          // Redo / Flip in the post-its. The neon-orange marker stripe
          // on hover (--marker-yellow) ties it to the heading marker
          // colour family.
          button(
            className := "post-it-action icon-only action-move",
            aria.label := "Submit move",
            tpe := "submit",
            icon("move")
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
    div(
      className := "controls",
      // Post-it 1 (yellow): board controls + Export. The icon-only row
      // covers "universal-meaning" actions; the text-only row covers the
      // data formats (no icon mapping fits FEN/PGN/JSON).
      div(
        className := "post-it-card",
        div(
          className := "post-it-row",
          iconOnlyButton(
            "undo",
            "Undo last move",
            () => postUndo()
          ),
          iconOnlyButton(
            "redo",
            "Redo move",
            () => postRedo()
          ),
          flipIconButton()
        ),
        h3(className := "post-it-subheading", "Export"),
        ul(
          className := "export-list",
          li(textActionButton("FEN", () => doExport("fen"))),
          li(textActionButton("PGN", () => doExport("pgn"))),
          li(textActionButton("JSON", () => doExport("json")))
        )
      ),
      // Post-it 2 (cyan): game-state actions — start a new one, load a
      // saved game, agree on a draw, resign, or shut down the server.
      // Cyan keeps the palette calmer than the previous coral; Quit
      // breaks out of the row visually via the `underlined` modifier so
      // the "shut everything down" option reads as distinct from the
      // in-game state changes.
      div(
        className := "post-it-card cyan",
        // Per-action classes (`action-new`, `action-forfeit`, etc.) drive
        // the per-button hover marker colour overrides in CSS so each
        // action carries its own emotional weight: green = positive
        // start, pink = give up, red = power off, neutral cyan default
        // for the in-game state changes (Load, Draw).
        actionButton(
          "new",
          "New Game",
          modifier = "action-new",
          () => askConfirm(confirmNewGame)
        ),
        actionButton(
          "load",
          "Load",
          modifier = "action-load",
          () => loadOpenVar.set(true)
        ),
        actionButton(
          "draw",
          "Draw",
          modifier = "action-draw",
          () => askConfirm(confirmDraw)
        ),
        actionButton(
          "forfeit",
          "Forfeit",
          modifier = "action-forfeit",
          () => askConfirm(confirmForfeit)
        )
      )
    )

  /** A doodle icon inlined as a `<span>` whose backing CSS rule (`.icon-…`)
    * sets `--icon-url` and the masking machinery — keeps Laminar-side code
    * declarative ("show the undo glyph") and the styling decisions (size,
    * colour, mask-mode) centralised in style.css.
    */
  private def icon(name: String): HtmlElement =
    span(className := s"icon icon-$name", aria.hidden := true)

  private def iconOnlyButton(
      iconName: String,
      ariaLabel: String,
      action: () => Unit
  ): HtmlElement =
    button(
      className := "post-it-action icon-only",
      aria.label := ariaLabel,
      onClick --> { _ => action() },
      icon(iconName)
    )

  /** Icon + text action button. The optional `modifier` adds an extra
    * class (e.g. `action-new`) so per-button styling — typically the
    * hover marker stripe colour — can target it without leaning on
    * positional selectors. Pass an empty string for "no extra class".
    */
  private def actionButton(
      iconName: String,
      label: String,
      modifier: String,
      action: () => Unit
  ): HtmlElement =
    val cls = if modifier.isEmpty then "post-it-action"
              else s"post-it-action $modifier"
    button(
      className := cls,
      onClick --> { _ => action() },
      icon(iconName),
      label
    )

  private def textActionButton(
      label: String,
      action: () => Unit
  ): HtmlElement =
    button(
      className := "post-it-action",
      onClick --> { _ => action() },
      label
    )

  /** Variant of [[actionButton]] with the `underlined` modifier and the
    * label text wrapped in a `<span class="label">` so the hand-drawn
    * underline pseudo can target the text only (full-width underline
    * stretching past the icon looked like a divider). Used exclusively on
    * Quit so the "shut down" option visually breaks out of its row.
    */
  private def underlinedActionButton(
      iconName: String,
      label: String,
      modifier: String,
      action: () => Unit
  ): HtmlElement =
    val cls =
      if modifier.isEmpty then "post-it-action underlined"
      else s"post-it-action underlined $modifier"
    button(
      className := cls,
      onClick --> { _ => action() },
      icon(iconName),
      span(className := "label", label)
    )

  // Flip toggles its own state, so both the aria-label and (potentially) the
  // icon need to track flippedVar. The icon stays the same — flipping a
  // flipped board is still "flip" — and the label flips Flip ↔ Unflip.
  private def flipIconButton(): HtmlElement =
    button(
      className := "post-it-action icon-only",
      aria.label <-- flippedVar.signal.map(f =>
        if f then "Unflip board" else "Flip board"
      ),
      onClick --> { _ => flippedVar.update(!_) },
      icon("flip")
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

  private val confirmForfeit: ConfirmRequest = ConfirmRequest(
    title = "Forfeit the game?",
    message = "The current player resigns; the opponent wins.",
    confirmLabel = "Forfeit",
    destructive = true,
    action = () => postForfeit()
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

  private val postCreateGameClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postCreateGame,
      None,
      backend
    )
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
  private val postForfeitClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postForfeit,
      None,
      backend
    )

  // Track the SSE handle so we can close + reopen it whenever gameIdVar
  // flips to a new id (e.g. after `new game`).
  private var sseHandle: Option[dom.EventSource] = None

  /** Mint a fresh game (or load one), capture its id, and resubscribe SSE
    * to that game's stream. Used both at first paint and whenever the
    * user clicks "new game" or loads a serialized payload.
    */
  private def bootstrapGame(load: Option[String] = None): Unit =
    postCreateGameClient((sessionId, CreateGameRequest(load))).foreach {
      case Right(snapshot) =>
        gameIdVar.set(Some(snapshot.id))
        stateVar.set(Some(snapshot.state))
        connectEvents(snapshot.id)
      case Left(err) =>
        showToast(err.error)
    }

  private def connectEvents(gameId: String): Unit =
    sseHandle.foreach(_.close())
    val source = new dom.EventSource(s"/api/games/$gameId/events")
    sseHandle = Some(source)
    source.addEventListener(
      "state",
      (e: dom.MessageEvent) =>
        e.data.asInstanceOf[String].fromJson[BoardStateDto] match
          case Right(state) => stateVar.set(Some(state))
          case Left(err)    => showToast(s"Bad state payload: $err")
    )

  private def handleStateResult(
      result: Either[ErrorDto, StateResponse]
  ): Unit =
    result match
      case Right(StateResponse.View(state)) => stateVar.set(Some(state))
      case Right(_: StateResponse.Export)   => ()
      case Left(err)                        => showToast(err.error)

  /** Run a request that needs the current gameId; toasts if no game is
    * active (shouldn't normally happen post-bootstrap).
    */
  private def withGameId(
      action: String => Future[Either[ErrorDto, BoardStateDto]]
  ): Unit =
    gameIdVar.now() match
      case Some(id) => postAndToastErrors(action(id))
      case None     => showToast("No active game")

  private def postMove(move: String): Unit =
    gameIdVar.now() match
      case Some(id) =>
        postMoveClient((id, sessionId, MoveRequest(move))).foreach {
          case Right(_)  => ()
          case Left(err) => showToast(err.error)
        }
      case None => showToast("No active game")

  private def postUndo(): Unit = withGameId(id => postUndoClient((id, sessionId)))
  private def postRedo(): Unit = withGameId(id => postRedoClient((id, sessionId)))
  private def postDraw(): Unit = withGameId(id => postDrawClient((id, sessionId)))
  private def postNew(): Unit = bootstrapGame(load = None)
  private def postForfeit(): Unit =
    withGameId(id => postForfeitClient((id, sessionId)))

  private def postLoad(raw: String): Unit = bootstrapGame(load = Some(raw))

  private def doExport(format: String): Unit =
    gameIdVar.now() match
      case Some(id) =>
        getStateClient((id, Some(format))).foreach {
          case Right(StateResponse.Export(resp)) => exportVar.set(Some(resp))
          case Right(_: StateResponse.View)      => ()
          case Left(err)                         => showToast(err.error)
        }
      case None => showToast("No active game")

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
  // Pointer-driven drag + drop
  //
  // We use pointer events (not HTML5 native drag-and-drop). The native API
  // hands us a `setDragImage` snapshot path that doesn't reliably wait for
  // inline SVG with cross-document `<use>` references — exactly what our
  // chess pieces are. Pointer events are simpler: hide the source piece
  // directly, render a floating clone that follows the cursor every frame
  // via CSS transform, hit-test the drop target via `elementFromPoint`.
  // Touch + mouse are unified by the browser; no separate code paths.
  // --------------------------------------------------------------------------

  private def handlePointerDown(
      pos: String,
      name: String,
      pieceColor: String,
      e: dom.PointerEvent,
  ): Unit =
    // Ignore non-primary buttons (right-click, middle-click) and any
    // pointers other than the first finger — chess is a one-pointer
    // gesture. Also bail out if a drag is already in flight or the
    // promotion modal is up.
    if e.button != 0 || !e.isPrimary then ()
    else if dragVar.now().isDefined then ()
    else if pendingPromotionVar.now().isDefined then ()
    else
      val el = e.currentTarget.asInstanceOf[dom.html.Element]
      val rect = el.getBoundingClientRect()
      // No `setPointerCapture` — we use document-level pointermove/up
      // listeners instead. Capture turned out to be unreliable once the
      // source piece got `pointer-events: none`, with implicit capture
      // release in some engines. Document listeners always fire.
      dragVar.set(
        Some(
          DragState(
            fromPos = pos,
            pieceName = name,
            pieceColorClass = s"$pieceColor-piece",
            pointerId = e.pointerId,
            offsetX = e.clientX - rect.left,
            offsetY = e.clientY - rect.top,
            cursorX = e.clientX,
            cursorY = e.clientY,
            moved = false,
          )
        )
      )
      e.preventDefault()

  /** Document-level pointermove handler. Cheap no-op when no drag is in
    * flight; otherwise stores the latest cursor coordinates and queues a
    * single `requestAnimationFrame` to flush them into `dragVar`. Pointer
    * events fire at 240+ Hz on modern devices but the display only paints
    * at 60–120 Hz — coalescing here means at most one `dragVar.set` per
    * frame regardless of pointer rate. This is the same pattern lichess's
    * chessground uses around `pieceMove`. The 4 px threshold flips
    * `moved = true` so a stationary click on a piece doesn't get
    * interpreted as a zero-distance drop.
    */
  private def handleGlobalPointerMove(e: dom.PointerEvent): Unit =
    if dragVar.now().isDefined then
      latestPointerX = e.clientX
      latestPointerY = e.clientY
      if !rafQueued then
        rafQueued = true
        dom.window.requestAnimationFrame { _ =>
          rafQueued = false
          dragVar.now().foreach { st =>
            val movedNow = st.moved ||
              math.hypot(
                latestPointerX - st.cursorX,
                latestPointerY - st.cursorY
              ) > 4
            dragVar.set(
              Some(
                st.copy(
                  cursorX = latestPointerX,
                  cursorY = latestPointerY,
                  moved = movedNow,
                )
              )
            )
          }
        }

  /** Document-level pointerup handler. Reads the live BoardStateDto out
    * of `stateVar.now()` so it works even if `dragVar`'s captured state
    * has gone stale relative to the latest server snapshot. Hit-tests
    * the drop target by pure coordinate math against the board's
    * bounding rect — `elementFromPoint` was unreliable because the
    * floating clone's `<span class="piece">` inherits
    * `pointer-events: auto` from the `.piece` rule's specificity and
    * intercepts the hit.
    */
  private def handleGlobalPointerUp(e: dom.PointerEvent): Unit =
    dragVar.now().foreach { st =>
      // `st.moved` is only flipped to true *inside* the rAF callback,
      // so on a fast drag the rAF may not have fired before pointerup —
      // dragVar still reads `moved = false` even though the user clearly
      // dragged. Re-derive movement from the up event's coordinates
      // against the last flushed cursor (which is the pointerdown coord
      // until the first rAF runs). Either we already crossed threshold
      // mid-drag (`st.moved`) or the up event itself is far enough from
      // the last flushed position.
      val finalMoved = st.moved ||
        math.hypot(
          e.clientX - st.cursorX,
          e.clientY - st.cursorY
        ) > 4
      if finalMoved then
        stateVar.now().foreach { state =>
          squareFromCoords(e.clientX, e.clientY).foreach { to =>
            attemptMove(st.fromPos, to, state)
          }
        }
    }
    // Reset the rAF latch and the latest-pointer cache so a queued rAF
    // from this drag (if any) can't overwrite a freshly-started next
    // drag with stale coordinates.
    rafQueued = false
    latestPointerX = 0.0
    latestPointerY = 0.0
    dragVar.set(None)

  /** Body of the previous `handleDrop` — extracted so both pointerup and
    * (someday) keyboard moves can call it.
    */
  private def attemptMove(
      from: String,
      to: String,
      state: BoardStateDto,
  ): Unit =
    if from == to then ()
    else if Logic.isPawnPromotion(from, to, state) then
      pendingPromotionVar.set(Some(PendingPromotion(from, to)))
    else postMove(s"$from $to")

  /** Pure-arithmetic hit-test. The board is always 8 × 8 with no gaps,
    * so given its bounding rect we can compute the target square
    * directly from the cursor position — no DOM walk, no
    * `elementFromPoint`, no interference from the floating clone or any
    * other overlay. Returns `None` when the cursor is outside the rect
    * (drop into the sidebar / off the page is a no-op).
    */
  private def squareFromCoords(x: Double, y: Double): Option[String] =
    val el = dom.document
      .querySelector(".board")
      .asInstanceOf[dom.html.Element]
    if el == null then None
    else
      val rect = el.getBoundingClientRect()
      if x < rect.left || x >= rect.right ||
         y < rect.top || y >= rect.bottom
      then None
      else
        val flipped = flippedVar.now()
        val fileIdx =
          ((x - rect.left) / (rect.width / 8)).toInt.max(0).min(7)
        val rankIdx =
          ((y - rect.top) / (rect.height / 8)).toInt.max(0).min(7)
        val file =
          if flipped then ('h'.toInt - fileIdx).toChar
          else ('a'.toInt + fileIdx).toChar
        val rank =
          if flipped then (1 + rankIdx).toString
          else (8 - rankIdx).toString
        Some(s"$file$rank")

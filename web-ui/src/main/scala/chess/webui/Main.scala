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
  StackInfoResponse,
  StateResponse
}
import chess.webui.components.{Components, ModalRegistry}
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
  /** Display nickname. Editable in Settings; persisted to localStorage so a
    * refresh keeps it. Default "Anonymous" if never set. */
  private val nicknameVar: Var[String] = Var(
    Option(dom.window.localStorage.getItem("pichess.nickname"))
      .filter(_.trim.nonEmpty)
      .getOrElse("Anonymous")
  )
  /** Discord-style four-digit suffix, generated once and pinned to the
    * session. Two `Alice`s on the same gateway are distinguishable as
    * `Alice#0473` vs `Alice#1209`. The hash isn't used for any auth — it's
    * a UI convenience for distinguishing same-named players.
    */
  private val playerHash: String =
    Option(dom.window.localStorage.getItem("pichess.hash"))
      .filter(_.matches("\\d{4}"))
      .getOrElse {
        val fresh =
          ("0000" + scala.util.Random.nextInt(10000).toString).takeRight(4)
        dom.window.localStorage.setItem("pichess.hash", fresh)
        fresh
      }
  private def displayName(): String = s"${nicknameVar.now()}#$playerHash"
  /** Current game id. With multi-game routing the gateway no longer tracks
    * a single "active" game — the client owns this. The id flows from
    * the URL hash (`#game/<id>`) and is cached here for HTTP / SSE
    * consumers that don't read the URL directly.
    */
  private val gameIdVar: Var[Option[String]] = Var(None)

  // --- Phase 3 in-game annotation state ------------------------------------
  // Toggles persisted to localStorage so a refresh / fresh tab keeps the
  // user's last setting; off by default if never set.
  private def readBoolPref(key: String): Boolean =
    Option(dom.window.localStorage.getItem(key)).contains("true")
  private val movePreviewVar: Var[Boolean] =
    Var(readBoolPref("pichess.movePreview"))
  private val threatDetectionVar: Var[Boolean] =
    Var(readBoolPref("pichess.threatDetection"))
  // Currently-displayed legal-move destinations from a clicked source square.
  // Cleared on every new state push and on every toggle change.
  private val previewFromVar: Var[Option[String]] = Var(None)
  private val previewMovesVar: Var[Set[String]] = Var(Set.empty)
  // Squares of own pieces under attack — refreshed from /threats whenever
  // stateVar changes (and threatDetection is on).
  private val threatsVar: Var[Set[String]] = Var(Set.empty)
  // When the user clicks a threatened piece, /attackers populates this.
  // Cleared on state change.
  private val attackersVar: Var[Set[String]] = Var(Set.empty)

  /** Cached `/api/stack-info` payload. Fetched once at App mount;
    * surfaced as a chip on the Dev pages so an operator can tell
    * which backend the running gateway is configured for. */
  private val stackInfoVar: Var[Option[StackInfoResponse]] = Var(None)

  /** Top-level screen the user is on. Mirrors the URL hash — see
    * `parseHash` / `hashFor`. Treat the URL as the source of truth so
    * the back button just works.
    */
  enum Screen:
    case Start
    case NewGameMenu
    case Join
    case Lobby(inviteCode: String)
    case Game(gameId: String)
    case Settings
    case Help
    // Dev surface, gated by PICHESS_DEV (see `devMode` below). Routes
    // exist regardless of devMode (parseHash always recognises them) so
    // a deep-link still works; the dev pages themselves render a
    // "not enabled" message when devMode is false.
    case Dev
    case DevTest
    case DevCoverage
    case DevPerformance

  private val currentScreenVar: Var[Screen] = Var(Screen.Start)

  /** Whether the gateway exposes its dev surface (routes under /dev/…).
    * Set from the `<meta name="pichess-dev">` tag the gateway injects
    * per `PICHESS_DEV`. Read once at App mount; the Dev link on the
    * start screen is conditional on this flag. */
  private val devMode: Boolean =
    Option(
      dom.document
        .querySelector("meta[name='pichess-dev']")
        .asInstanceOf[dom.html.Meta]
    )
      .map(_.content.trim.toLowerCase)
      .contains("true")

  /** Translate `dom.window.location.hash` into a `Screen`. Anything we
    * don't recognise falls back to Start.
    */
  private def parseHash(raw: String): Screen =
    val stripped = raw.stripPrefix("#")
    stripped match
      case "" | "/"             => Screen.Start
      case "new"                => Screen.NewGameMenu
      case "join"               => Screen.Join
      case "settings"           => Screen.Settings
      case "help"               => Screen.Help
      case "dev"                => Screen.Dev
      case "dev/test"           => Screen.DevTest
      case "dev/test/coverage"  => Screen.DevCoverage
      case "dev/test/performance" => Screen.DevPerformance
      case s"lobby/$c"          => Screen.Lobby(c)
      case s"game/$id"          => Screen.Game(id)
      case _                    => Screen.Start

  private def hashFor(screen: Screen): String = screen match
    case Screen.Start          => ""
    case Screen.NewGameMenu    => "#new"
    case Screen.Join           => "#join"
    case Screen.Lobby(code)    => s"#lobby/$code"
    case Screen.Game(id)       => s"#game/$id"
    case Screen.Settings       => "#settings"
    case Screen.Help           => "#help"
    case Screen.Dev            => "#dev"
    case Screen.DevTest        => "#dev/test"
    case Screen.DevCoverage    => "#dev/test/coverage"
    case Screen.DevPerformance => "#dev/test/performance"

  /** Navigate to a different screen by mutating the URL hash. The
    * `hashchange` listener picks it up and updates `currentScreenVar`,
    * so this is the one and only entry point — never set the var
    * directly.
    */
  private def navigate(screen: Screen): Unit =
    val next = hashFor(screen)
    if dom.window.location.hash != next then dom.window.location.hash = next
    else syncScreenFromHash() // hashchange won't fire for an identical value

  private def syncScreenFromHash(): Unit =
    currentScreenVar.set(parseHash(dom.window.location.hash))
  private val loadOpenVar: Var[Boolean] = Var(false)
  private val loadInputVar: Var[String] = Var("")
  private val exportVar: Var[Option[ExportResponse]] = Var(None)
  // Help is rendered as an in-SPA view — not a separate route — so that the
  // browser back button returns to the game without a full page reload.
  // We sync this var with `location.hash` so deep-links (#help) and the back
  // button just work via hashchange events.
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
        // The URL hash drives the active screen. `syncScreenFromHash` runs
        // once at boot to pick up a deep-link, then on every hashchange
        // (back/forward, manual edits, anchor links).
        syncScreenFromHash()
        dom.window.addEventListener(
          "hashchange",
          (_: dom.Event) => syncScreenFromHash()
        )
        // Whenever we arrive on a Game screen, make sure the game state
        // is loaded for that id — refresh state + (re)open SSE for it.
        currentScreenVar.signal.distinct.foreach {
          case Screen.Game(id) => enterGame(id)
          case _               => ()
        }(using ctx.owner)
        // Phase 3: any state change (move, undo, redo, SSE push) clears
        // the per-click preview and triggers a fresh /threats fetch
        // when the toggle is on. `.distinct` so a no-op set doesn't
        // re-fire the network call.
        stateVar.signal.distinct.foreach { _ =>
          clearPreviewState()
          if threatDetectionVar.now() then refreshThreats()
        }(using ctx.owner)
        // Toggling threatDetection on should immediately fetch /threats so
        // the rings appear without waiting for the next move; toggling off
        // should clear them. Same subscription persists the new value to
        // localStorage so the setting survives a reload.
        threatDetectionVar.signal.distinct.foreach { on =>
          dom.window.localStorage.setItem("pichess.threatDetection", on.toString)
          if on then refreshThreats()
          else threatsVar.set(Set.empty)
        }(using ctx.owner)
        // movePreview off → drop any in-flight overlay so the board
        // returns to a clean baseline. Also persisted.
        movePreviewVar.signal.distinct.foreach { on =>
          dom.window.localStorage.setItem("pichess.movePreview", on.toString)
          if !on then clearPreviewState()
        }(using ctx.owner)
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
        // Modal scroll lock — see `ModalRegistry`. Each modal
        // self-registers with a stable name + its own open signal;
        // bindBodyClass() flips `body.modal-open` when the registry
        // becomes non-empty. New modals just add a register() call —
        // no Signal.combine needs to grow.
        {
          given Owner = ctx.owner
          ModalRegistry.bindBodyClass()
          ModalRegistry.register(
            "promotion",
            pendingPromotionVar.signal.map(_.isDefined)
          )
          ModalRegistry.register("load",    loadOpenVar.signal)
          ModalRegistry.register("confirm", confirmVar.signal.map(_.isDefined))
          ModalRegistry.register("export",  exportVar.signal.map(_.isDefined))
        }
        // Fetch the active stack identity once at boot. Used by the
        // Dev page's stack chip. Silent on failure — the chip just
        // doesn't render. Public endpoint, no gating.
        getStackInfoClient(()).foreach {
          case Right(info) => stackInfoVar.set(Some(info))
          case Left(_)     => ()
        }
      },
      // Crumpled-paper background lives at the App root so every screen
      // (start / lobby / settings / help / docs / game) shares the same
      // texture — was previously only mounted inside `mainUi()`. The svg
      // is `position: fixed; z-index: -1` so it sits behind everything.
      pageBackground(),
      child <-- currentScreenVar.signal.distinct.map(renderScreen),
      // Toast is rendered at App root (not inside mainUi) so error
      // messages from the lobby/start/settings screens are also visible.
      // Lobby create/join failures used to fire showToast silently because
      // the toast element only existed on the game screen.
      toastElement()
    )

  /** Top-level screen dispatcher. The Game screen reuses the existing
    * `mainUi()` body so the heavy chess UI doesn't get rewritten in
    * Phase 2. The new screens are deliberately unstyled — text, plain
    * buttons, simple inputs — visual polish comes later.
    */
  private def renderScreen(screen: Screen): HtmlElement = screen match
    case Screen.Start           => startScreen()
    case Screen.NewGameMenu     => newGameMenu()
    case Screen.Join            => joinScreen()
    case Screen.Lobby(code)     => lobbyScreen(code)
    case Screen.Game(_)         => mainUi()
    case Screen.Settings        => settingsScreen()
    case Screen.Help            => helpScreen()
    case Screen.Dev             => devIndexScreen()
    case Screen.DevTest         => devTestScreen()
    case Screen.DevCoverage     => devCoverageScreen()
    case Screen.DevPerformance  => devPerformanceScreen()

  /** Side-effect when entering a Game screen: ensure gameIdVar is set,
    * pull current state, (re)connect SSE for that id. Idempotent — a
    * navigation that lands on the same id won't double-subscribe because
    * `connectEvents` closes any existing stream first.
    */
  private def enterGame(id: String): Unit =
    gameIdVar.set(Some(id))
    getStateClient((id, None)).foreach(handleStateResult)
    connectEvents(id)

  private def mainUi(): HtmlElement =
    div(
      className := "app-shell",
      // pageBackground is now mounted at App root so it spans every
      // screen (see App()); no longer needed here.
      header(),
      // Help is its own routed screen now (#help) — the Game screen body
      // always shows the board.
      gameBody(),
      promotionOverlay(),
      loadModal(),
      exportModal(),
      confirmModal(),
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
      // Brand is a real anchor so right-click → open-in-new-tab + middle-
      // click work, and screen readers announce it as navigation. The
      // hash-based router picks up the `#` change via the popstate listener.
      a(
        className := "header-brand",
        href := "#",
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
        span("piChess")
      ),
      div(className := "header-spacer"),
      div(
        className := "header-actions",
        gameHeaderActions
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

  // --------------------------------------------------------------------------
  // Board
  // --------------------------------------------------------------------------

  private def boardArea(): HtmlElement =
    // Board sits on a sheet of crumpled paper. statusIndicator at the top
    // (panel header). board-row puts board-wrapper next to capturedPile —
    // captured pieces stick to the right of the board, mirroring the
    // rank-label gutter on the left. Below the paper, `.board-post-its`
    // is a row of three equal-sized square sticky notes that overhang
    // the board's bottom edge via a negative top margin.
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
      ),
      boardPostIt()
    )

  /** Merged yellow post-it that sticks to the bottom of the board.
    * Top row holds the three board-view actions (Undo / Redo / Flip);
    * below that, Export and Annotations sit as two side-by-side
    * columns so the card stays denser than the previous one-section-
    * per-post-it layout. */
  private def boardPostIt(): HtmlElement =
    div(
      className := "post-it-shadow board-post-it",
      div(
        className := "post-it-card",
        div(
          className := "post-it-row",
          actionButton("undo", "Undo", modifier = "", () => postUndo()),
          actionButton("redo", "Redo", modifier = "", () => postRedo()),
          flipActionButton()
        ),
        div(
          className := "board-post-it-cols",
          // Left column — Export.
          div(
            className := "board-post-it-col",
            h3(className := "post-it-subheading", "Export"),
            ul(
              className := "export-list",
              li(textActionButton("FEN", () => doExport("fen"))),
              li(textActionButton("PGN", () => doExport("pgn"))),
              li(textActionButton("JSON", () => doExport("json")))
            )
          ),
          // Right column — annotation toggles.
          div(
            className := "board-post-it-col",
            h3(className := "post-it-subheading", "Annotations"),
            Components.checkboxRow(movePreviewVar,     "Moves"),
            Components.checkboxRow(threatDetectionVar, "Threats")
          )
        )
      )
    )

  /** Cyan post-it: game-state actions — start a new game, load a saved
    * one, agree on a draw, resign. Per-action classes
    * (`action-new`, `action-forfeit`, …) drive the per-button hover
    * marker colour overrides in CSS so each carries its own emotional
    * weight (green starter, pink give-up, etc.). */
  private def gameStatePostIt(): HtmlElement =
    div(
      className := "post-it-shadow cyan",
      div(
        className := "post-it-card cyan",
        actionButton("new",     "New Game", "action-new",     () => askConfirm(confirmNewGame)),
        actionButton("load",    "Load",     "action-load",    () => loadOpenVar.set(true)),
        actionButton("draw",    "Draw",     "action-draw",    () => askConfirm(confirmDraw)),
        actionButton("forfeit", "Forfeit",  "action-forfeit", () => askConfirm(confirmForfeit))
      )
    )

  /** Flip / unflip board button — both the label and the aria-label
    * track `flippedVar` so the button text always names the *destination*
    * of the action (Flip while upright, Unflip while flipped). Used in
    * the compact board-controls post-it below the board. */
  private def flipActionButton(): HtmlElement =
    button(
      className := "post-it-action",
      aria.label <-- flippedVar.signal.map(f =>
        if f then "Unflip board" else "Flip board"
      ),
      onClick --> { _ => flippedVar.update(!_) },
      icon("flip"),
      child.text <-- flippedVar.signal.map(f => if f then "Unflip" else "Flip")
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
      // Phase 3 annotation classes — driven by the per-tab toggles +
      // server responses to /legal-moves, /threats, /attackers. Each is
      // wired through `cls(name) <-- signal` so toggling the var flips
      // the class without re-rendering the whole board.
      cls("is-preview-source") <-- previewFromVar.signal
        .map(_.contains(sq.pos))
        .distinct,
      cls("is-preview-dest") <-- previewMovesVar.signal
        .map(_.contains(sq.pos))
        .distinct,
      cls("is-threatened") <-- threatsVar.signal
        .map(_.contains(sq.pos))
        .distinct,
      cls("is-attacker") <-- attackersVar.signal
        .map(_.contains(sq.pos))
        .distinct,
      onClick --> { _ => onSquareClick(sq) },
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
      // Move log sits at the top; the cyan game-state post-it (New /
      // Load / Draw / Forfeit) hangs below it. The board-local controls
      // (Undo / Redo / Flip + annotation toggles + Export) live in the
      // merged yellow post-it under the board itself.
      moveLogContainer(),
      gameStatePostIt()
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
        // "Moves" heading as a newspaper clipping — the Special Elite
        // font + cut-paper background ties it to the same design rule
        // used on the help page (Special Elite → newsprint clipping)
        // rather than reading as another button.
        h2(
          className := "section-title",
          span(
            className := "newsprint-shadow",
            span(className := "code-inline", "Moves")
          )
        ),
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
          // input itself can stay borderless. `min-w-[8rem]` is the
          // wrap-floor: when the sidebar narrows, the input shrinks
          // to that width before the form wraps onto a new line.
          span(
            className := "text-field-wrap min-w-[8rem]",
            input(
              tpe := "text",
              className := "text-field",
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

  /** A doodle icon inlined as a `<span>` whose backing CSS rule (`.icon-…`)
    * sets `--icon-url` and the masking machinery — keeps Laminar-side code
    * declarative ("show the undo glyph") and the styling decisions (size,
    * colour, mask-mode) centralised in style.css.
    */
  private def icon(name: String): HtmlElement =
    span(className := s"icon icon-$name", aria.hidden := true)

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
          className := "modal-actions flex flex-row gap-3 justify-end",
          Components.ctaButton("Load") { _ =>
            val raw = loadInputVar.now().trim
            if raw.nonEmpty then
              postLoad(raw)
              loadInputVar.set("")
              loadOpenVar.set(false)
          },
          Components.secondaryButton("Cancel") { _ => loadOpenVar.set(false) }
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
          className := "modal-actions flex flex-row gap-3 justify-end",
          Components.secondaryButton("Cancel") { _ => confirmVar.set(None) },
          // The confirm button swaps between destructive (coral) and
          // secondary (cyan) based on the request's `destructive` flag.
          // Build it inline so the className can flip reactively
          // — Components helpers hard-code their variant class.
          button(
            typ := "button",
            className <-- confirmVar.signal.map(c =>
              if c.exists(_.destructive) then
                "btn-destructive inline-flex items-center justify-center px-6 py-2 cursor-pointer outline-none"
              else
                "btn-secondary inline-flex items-center justify-center px-6 py-2 cursor-pointer outline-none"
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
  private val getLegalMovesClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.getLegalMoves,
      None,
      backend
    )
  private val getThreatsClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.getThreats,
      None,
      backend
    )
  private val getAttackersClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.getAttackers,
      None,
      backend
    )
  private val getStackInfoClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.getStackInfo,
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

  // --------------------------------------------------------------------------
  // Phase 3: annotation overlay handlers (move preview + threat detection).
  // --------------------------------------------------------------------------

  /** Square click handler. Drives both the click-to-preview interaction
    * (movePreview) and click-to-move when a destination on the existing
    * preview is selected. With both toggles off this is a no-op so the
    * existing drag-to-move flow is unaffected.
    */
  private def onSquareClick(sq: SquareDto): Unit =
    if !movePreviewVar.now() then ()
    else
      val pos = sq.pos
      val previewing = previewFromVar.now()
      val moves = previewMovesVar.now()
      stateVar.now() match
        case None => ()
        case Some(state) =>
          if previewing.contains(pos) then
            // Clicking the source again deselects.
            clearPreviewState()
          else if previewing.isDefined && moves.contains(pos) then
            // Click-to-move: a preview is open and the user clicked a
            // destination square — fire the move just like a drag drop.
            attemptMove(previewing.get, pos, state)
            clearPreviewState()
          else
            sq.piece match
              case Some(_) if sq.pieceColor.contains(state.activeColor) =>
                fetchPreview(pos)
                if threatDetectionVar.now() && threatsVar.now().contains(pos)
                then fetchAttackers(pos)
                else attackersVar.set(Set.empty)
              case _ =>
                clearPreviewState()

  private def clearPreviewState(): Unit =
    previewFromVar.set(None)
    previewMovesVar.set(Set.empty)
    attackersVar.set(Set.empty)

  private def fetchPreview(from: String): Unit =
    gameIdVar.now() match
      case None => ()
      case Some(id) =>
        getLegalMovesClient((id, from)).foreach {
          case Right(resp) =>
            previewFromVar.set(Some(from))
            previewMovesVar.set(resp.moves.toSet)
          case Left(err) =>
            showToast(err.error)
            clearPreviewState()
        }

  private def fetchAttackers(of: String): Unit =
    gameIdVar.now() match
      case None => ()
      case Some(id) =>
        getAttackersClient((id, of)).foreach {
          case Right(resp) => attackersVar.set(resp.attackers.toSet)
          case Left(_)     => attackersVar.set(Set.empty)
        }

  /** Refresh the threat overlay for the active color. Called whenever
    * stateVar changes (and threatDetection is on) so the red rings stay
    * in sync after every move from any source (drag, click, SSE push).
    */
  private def refreshThreats(): Unit =
    gameIdVar.now() match
      case None => threatsVar.set(Set.empty)
      case Some(id) =>
        getThreatsClient(id).foreach {
          case Right(resp) => threatsVar.set(resp.threatened.toSet)
          case Left(_)     => threatsVar.set(Set.empty)
        }

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

  // --------------------------------------------------------------------------
  // Phase 2 screens (unstyled — functionality first; visual polish later)
  // --------------------------------------------------------------------------

  // All lobby calls go through the gateway's reverse proxy under
  // /lobbies/... (see chess.controller.LobbyProxy). Empty base = same-
  // origin request, which means no CORS preflight and no need for the
  // lobby-service to advertise CORS headers. The gateway forwards
  // verbatim to whichever lobby-service URL it was configured with.
  private val lobbyBaseUrl: String = ""

  /** Holds the lobby currently being rendered on the Lobby screen. */
  private val currentLobbyVar: Var[Option[LobbyJson]] = Var(None)
  /** Browse list for the Join screen. */
  private val publicLobbiesVar: Var[List[LobbyJson]] = Var(Nil)

  /** Wire-shape of a Lobby JSON record. We don't depend on the
    * lobby-service's internal `chess.lobby` package from web-ui, so this
    * is a small mirror of the fields the screens actually read.
    */
  private case class LobbyJson(
      id: String,
      inviteCode: String,
      hostNickname: String,
      hostSessionId: String,
      guestNickname: js.UndefOr[String],
      guestSessionId: js.UndefOr[String],
      visibility: String,
      allowUndo: Boolean,
      allowSpectate: Boolean,
      spectatorLimit: Int,
      status: String,
      createdAt: js.UndefOr[Double],
      gameId: js.UndefOr[String]
  )

  /** Tiny JSON helpers — the lobby screens only need a handful of lobby
    * roundtrips and we don't want to wire a second sttp + Tapir client
    * setup just for that. Uses the browser's native fetch + JSON.parse.
    */
  private def fetchJson(
      method: String,
      url: String,
      body: Option[String]
  ): scala.concurrent.Future[String] =
    val init = new dom.RequestInit {}
    init.method = method.asInstanceOf[dom.HttpMethod]
    val headers = new dom.Headers()
    headers.append("Content-Type", "application/json")
    headers.append("Accept", "application/json")
    headers.append("X-Session-Id", sessionId)
    init.headers = headers
    body.foreach(b => init.body = b)
    dom.window
      .fetch(url, init)
      .toFuture
      .flatMap(r =>
        r.text().toFuture.map(t =>
          if r.ok then t
          else throw new RuntimeException(s"HTTP ${r.status}: $t")
        )
      )

  private def parseLobbyJson(raw: String): Option[LobbyJson] =
    try
      val obj = js.JSON.parse(raw).asInstanceOf[js.Dynamic]
      Some(
        LobbyJson(
          id = obj.id.asInstanceOf[String],
          inviteCode = obj.inviteCode.asInstanceOf[String],
          hostNickname = obj.hostNickname.asInstanceOf[String],
          hostSessionId = obj.hostSessionId.asInstanceOf[String],
          guestNickname = obj.guestNickname.asInstanceOf[js.UndefOr[String]],
          guestSessionId =
            obj.guestSessionId.asInstanceOf[js.UndefOr[String]],
          visibility = obj.visibility.asInstanceOf[String],
          allowUndo = obj.allowUndo.asInstanceOf[Boolean],
          allowSpectate = obj.allowSpectate.asInstanceOf[Boolean],
          spectatorLimit = obj.spectatorLimit.asInstanceOf[Int],
          status = obj.status.asInstanceOf[String],
          createdAt = obj.createdAt.asInstanceOf[js.UndefOr[Double]],
          gameId = obj.gameId.asInstanceOf[js.UndefOr[String]]
        )
      )
    catch case _: Throwable => None

  private def parseLobbyList(raw: String): List[LobbyJson] =
    try
      val obj = js.JSON.parse(raw).asInstanceOf[js.Dynamic]
      val arr = obj.lobbies.asInstanceOf[js.Array[js.Dynamic]]
      arr.toList.flatMap(d => parseLobbyJson(js.JSON.stringify(d)))
    catch case _: Throwable => Nil

  // -- Start screen ---------------------------------------------------------

  /** Start screen — uses the canonical layout helpers (`screenLayout` /
    * `titleCard` / `contentCard` / `sidePostIt` / `linkButton` /
    * `linkAnchor`) per design.md §6 + §12.3. The only screen-local bit
    * is the brand wordmark inside the title card (poster-sized peach +
    * "piChess") and the decorative `pieceShelf()` along the bottom
    * — both are landing-page-specific decorations.
    */
  private def startScreen(): HtmlElement =
    Components.screenLayout("start")(
      Components.titleCard(startBrand()),
      Components.contentCard(
        div(
          className := "flex flex-col gap-1 items-center",
          Components.linkButton("New Game") { _ => navigate(Screen.NewGameMenu) },
          Components.linkButton("Join")     { _ => navigate(Screen.Join) },
          Components.linkButton("Settings") { _ => navigate(Screen.Settings) }
        ),
        Components.sidePostIt(
          // /docs is the Tapir-generated Swagger UI served by the gateway;
          // open it in its own tab rather than wrapping the iframe in our
          // own styled page (the wrapper read as visually broken — Swagger
          // styles fight the notebook look).
          a(
            className := "btn-link",
            href := "/docs",
            target := "_blank",
            rel := "noopener noreferrer",
            "Docs"
          ),
          Components.linkAnchor("Help", "#help"),
          // Dev surface only shows when the gateway was launched with
          // PICHESS_DEV=true. Operator-only entry point — regular users
          // never see it.
          if devMode then Components.linkAnchor("Dev", "#dev")
          else emptyNode
        )
      ),
      pieceShelf()
    )

  /** Poster-sized brand wordmark used inside the start-screen title
    * card. Reuses the same peach SVG + "piChess" markup as the header,
    * just scaled up via the `.start-brand` bespoke rule. */
  private def startBrand(): HtmlElement =
    div(
      className := "start-brand",
      svg.svg(
        svg.viewBox := "-3 -3 43 44",
        svg.cls := "start-logo",
        svg.use(svg.href := "/web/peach.svg#peach")
      ),
      span("piChess")
    )

  /** Decorative row of large chess pieces along the bottom of the start
    * screen. Both colours, all six piece types — laid out left to right
    * with a small randomised tilt per piece so the row reads as
    * hand-arranged rather than a sterile grid. Pure presentation —
    * non-interactive (`pointer-events: none` in CSS).
    */
  private def pieceShelf(): HtmlElement =
    val pieces: List[(String, String)] = List(
      "rook"   -> "white",
      "knight" -> "white",
      "bishop" -> "white",
      "queen"  -> "white",
      "king"   -> "white",
      "pawn"   -> "white",
      "pawn"   -> "black",
      "king"   -> "black",
      "queen"  -> "black",
      "bishop" -> "black",
      "knight" -> "black",
      "rook"   -> "black"
    )
    div(
      className := "start-piece-shelf",
      pieces.map { case (name, color) =>
        // Per-piece tilt: -8°..+8°, picked once at render time so each
        // page load gets a different arrangement (matches the rest of
        // the start screen's "fresh notebook page" feeling).
        val tilt = scala.util.Random.between(-8.0, 8.0)
        val drop = scala.util.Random.between(-0.4, 0.4)  // small vertical jitter
        span(
          className := s"shelf-piece $color-piece",
          styleAttr := s"transform: rotate(${"%.2f".format(tilt)}deg) " +
            s"translateY(${"%.2f".format(drop)}rem);",
          pieceSvg(name)
        )
      }
    )

  // -- Settings screen ------------------------------------------------------

  private def settingsScreen(): HtmlElement =
    Components.screenLayout("settings")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Settings")
      ),
      Components.contentCard(
        Components.formRow("Nickname")(
          span(
            className := "text-field-wrap",
            input(
              typ := "text",
              className := "text-field",
              value <-- nicknameVar.signal,
              onInput.mapToValue --> { v =>
                val cleaned = v.trim
                val effective = if cleaned.isEmpty then "Anonymous" else cleaned
                nicknameVar.set(effective)
                dom.window.localStorage.setItem("pichess.nickname", effective)
              }
            )
          )
        ),
        p(
          className := "settings-hint",
          "You'll appear as ",
          span(child.text <-- nicknameVar.signal.map(n => s"$n#$playerHash"))
        ),
        Components.formRow("Theme")(themeToggleButton())
      )
    )

  // -- Help / Docs ----------------------------------------------------------

  private def helpScreen(): HtmlElement =
    Components.screenLayout("help")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Help")
      ),
      // No outer content-card: each `.help-section` is its own paper
      // panel, so a wrapper card would just nest cards inside a card.
      HelpView.render()
    )

  // -- Dev section ----------------------------------------------------------

  /** Stack-identity chip — small handwritten badge naming the active
    * backend + any projection extras. Driven by `stackInfoVar`, which
    * is populated once at App mount from `/api/stack-info`. Shown only
    * when the fetch succeeded. */
  private def stackChip(): HtmlElement =
    div(
      className := "stack-chip flex flex-row items-center gap-2",
      child <-- stackInfoVar.signal.map {
        case None       => span(className := "stack-chip-empty", "")
        case Some(info) =>
          val extrasSuffix =
            if info.extras.isEmpty then ""
            else s" + ${info.extras.mkString(", ")}"
          span(
            className := "stack-chip-text",
            "Active stack: ",
            strong(className := "font-press", info.backend),
            extrasSuffix
          )
      }
    )

  /** "Dev mode isn't enabled" placeholder. Shown on any /dev/...
    * screen when the gateway didn't ship `PICHESS_DEV=true` — better
    * than a 404 since the URL still routes (the operator can tell
    * they got here, just that the surface is off). */
  private def devDisabledNotice(): HtmlElement =
    Components.contentCard(
      p(
        className := "settings-hint",
        "Dev tools are not enabled in this deployment. Restart the gateway with ",
        code(className := "font-press", "PICHESS_DEV=true"),
        " to surface the coverage / performance / docs pages."
      )
    )

  private def devIndexScreen(): HtmlElement =
    Components.screenLayout("dev")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Dev")
      ),
      if !devMode then devDisabledNotice()
      else
        Components.contentCard(
          stackChip(),
          p(
            className := "settings-hint",
            "Switch stacks from the terminal: ",
            code(className := "font-press", "make stack-postgres"),
            ", ",
            code(className := "font-press", "make stack-mongo"),
            ", etc."
          ),
          div(
            className := "flex flex-col gap-2 items-stretch",
            a(
              className := "btn-link",
              href := "/docs",
              target := "_blank",
              rel := "noopener noreferrer",
              "API docs (Swagger) ↗"
            ),
            Components.linkAnchor("Tests + reports",   "#dev/test")
          )
        )
    )

  private def devTestScreen(): HtmlElement =
    Components.screenLayout("dev-test")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Dev)),
        Components.screenHeading("Tests")
      ),
      if !devMode then devDisabledNotice()
      else
        Components.contentCard(
          stackChip(),
          div(
            className := "flex flex-col gap-2 items-stretch",
            Components.linkAnchor("Coverage report",    "#dev/test/coverage"),
            Components.linkAnchor("Performance report", "#dev/test/performance")
          )
        )
    )

  private def devCoverageScreen(): HtmlElement =
    Components.screenLayout("dev-coverage")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.DevTest)),
        Components.screenHeading("Coverage")
      ),
      if !devMode then devDisabledNotice()
      else
        Components.contentCard(
          p(
            className := "settings-hint",
            "Baked into the gateway image by ",
            code(className := "font-press", "make coverage-build"),
            ". Re-run that target + ",
            code(className := "font-press", "make dev-gateway"),
            " to refresh."
          ),
          iframe(
            src := "/dev/coverage/report/",
            className := "docs-iframe w-full h-[80vh] border border-hairline"
          )
        )
    )

  private def devPerformanceScreen(): HtmlElement =
    Components.screenLayout("dev-performance")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.DevTest)),
        Components.screenHeading("Performance")
      ),
      if !devMode then devDisabledNotice()
      else
        Components.contentCard(
          p(
            className := "settings-hint",
            "Generated by ",
            code(className := "font-press", "make gatling-build"),
            " and baked into the gateway image. Each run lands in ",
            code(className := "font-press", "/dev/performance/report/"),
            "."
          ),
          iframe(
            src := "/dev/performance/report/",
            className := "docs-iframe w-full h-[80vh] border border-hairline"
          )
        )
    )

  // -- New-game menu --------------------------------------------------------

  // -- New-game screen ------------------------------------------------------

  /** Three game modes the user can choose between on the new-game screen. */
  enum NewGameMode:
    case Local, Host, Bot

  /** Currently-selected tab. Local first because it's the fastest path
    * (zero clicks of configuration). */
  private val newGameModeVar: Var[NewGameMode] = Var(NewGameMode.Local)

  /** Host-game form state. Submitted via `createHostedLobby`. */
  private val hostVisibilityVar: Var[String] = Var("Public")
  private val hostAllowUndoVar: Var[Boolean] = Var(true)
  private val hostAllowSpectateVar: Var[Boolean] = Var(true)
  private val hostSpectatorLimitVar: Var[Int] = Var(8)

  private def newGameMenu(): HtmlElement =
    Components.screenLayout("new-game")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("New Game")
      ),
      Components.contentCard(
        Components.tabStrip[NewGameMode](
          newGameModeVar,
          Seq(
            (NewGameMode.Local, "Local",  true),
            (NewGameMode.Host,  "Host",   true),
            (NewGameMode.Bot,   "Vs Bot", false)
          )
        ),
        child <-- newGameModeVar.signal.distinct.map(modeDetails)
      )
    )

  private def modeDetails(mode: NewGameMode): HtmlElement = mode match
    case NewGameMode.Local => localModeDetails()
    case NewGameMode.Host  => hostModeDetails()
    case NewGameMode.Bot   => botModeDetails()

  private def localModeDetails(): HtmlElement =
    div(
      className := "mode-details flex flex-col gap-4 items-stretch",
      p(
        className := "mode-blurb",
        "Both colours are played from this browser tab. " +
          "No invite code, no opponent — just a board."
      ),
      Components.linkButton("Start local game") { _ => createLocalGame() }
    )

  private def botModeDetails(): HtmlElement =
    div(
      className := "mode-details flex flex-col gap-4 items-stretch",
      p(
        className := "mode-blurb",
        "Coming soon — engine integration is on the roadmap."
      )
    )

  private def hostModeDetails(): HtmlElement =
    div(
      className := "mode-details flex flex-col gap-4 items-stretch",
      div(
        className := "host-form flex flex-col gap-3",
        Components.formRow("Visibility")(
          Components.selectInput(
            hostVisibilityVar,
            Seq("Public" -> "Public", "Private" -> "Private")
          )
        ),
        Components.checkboxRow(hostAllowUndoVar,     "Allow undo"),
        Components.checkboxRow(hostAllowSpectateVar, "Allow spectators"),
        Components.formRow("Spectator limit")(
          Components.numberInput(hostSpectatorLimitVar, min = 0, max = 64)
        )
      ),
      Components.linkButton("Create lobby") { _ => createHostedLobby() }
    )

  private def createHostedLobby(): Unit =
    val payload = js.JSON.stringify(
      js.Dynamic.literal(
        hostNickname = nicknameVar.now(),
        hostSessionId = sessionId,
        visibility = hostVisibilityVar.now(),
        allowUndo = hostAllowUndoVar.now(),
        allowSpectate = hostAllowSpectateVar.now(),
        spectatorLimit = hostSpectatorLimitVar.now()
      )
    )
    fetchJson("POST", s"$lobbyBaseUrl/lobbies", Some(payload)).onComplete {
      case scala.util.Success(raw) =>
        parseLobbyJson(raw) match
          case Some(l) =>
            currentLobbyVar.set(Some(l))
            navigate(Screen.Lobby(l.inviteCode))
          case None =>
            showToast("Could not parse lobby response")
      case scala.util.Failure(err) =>
        showToast(s"Create lobby failed: ${err.getMessage}")
    }

  private def createLocalGame(): Unit =
    postCreateGameClient((sessionId, CreateGameRequest(None))).foreach {
      case Right(snapshot) =>
        gameIdVar.set(Some(snapshot.id))
        stateVar.set(Some(snapshot.state))
        navigate(Screen.Game(snapshot.id))
      case Left(err) => showToast(err.error)
    }

  // -- Join screen ----------------------------------------------------------

  private val joinCodeVar: Var[String] = Var("")

  private def joinScreen(): HtmlElement =
    Components.screenLayout("join")(
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Join Game")
      ),
      Components.contentCard(
        onMountCallback { _ => refreshPublicLobbies() },
        Components.formRow("Invite code")(
          Components.textInput(joinCodeVar, placeholder := "ABCDEF")
        ),
        Components.ctaButton("Join") { _ => joinByCode(joinCodeVar.now()) },
        div(
          className := "join-public flex flex-col gap-2",
          div(
            className := "flex flex-row items-center justify-between",
            h2(className := "section-heading", "Public lobbies"),
            Components.iconButton("⟳") { _ => refreshPublicLobbies() }
          ),
          ul(
            className := "public-list flex flex-col gap-1 list-none p-0",
            children <-- publicLobbiesVar.signal.map(_.map { l =>
              li(
                className := "public-list-item flex flex-row items-center justify-between gap-3",
                span(s"${l.hostNickname} — ${l.inviteCode}"),
                Components.linkButton("Join") { _ => joinByCode(l.inviteCode) }
              )
            })
          )
        )
      )
    )

  private def refreshPublicLobbies(): Unit =
    fetchJson("GET", s"$lobbyBaseUrl/lobbies/public", None).onComplete {
      case scala.util.Success(raw) =>
        publicLobbiesVar.set(parseLobbyList(raw))
      case scala.util.Failure(err) =>
        showToast(s"Could not fetch lobbies: ${err.getMessage}")
    }

  private def joinByCode(rawCode: String): Unit =
    val code = rawCode.trim.toUpperCase
    if code.isEmpty then showToast("Enter an invite code first")
    else
      val payload = js.JSON.stringify(
        js.Dynamic.literal(
          guestNickname = nicknameVar.now(),
          guestSessionId = sessionId
        )
      )
      fetchJson(
        "POST",
        s"$lobbyBaseUrl/lobbies/by-code/$code/join",
        Some(payload)
      ).onComplete {
        case scala.util.Success(raw) =>
          parseLobbyJson(raw) match
            case Some(l) =>
              currentLobbyVar.set(Some(l))
              navigate(Screen.Lobby(l.inviteCode))
            case None => showToast("Bad lobby payload")
        case scala.util.Failure(err) =>
          showToast(s"Join failed: ${err.getMessage}")
      }

  // -- Lobby waiting room ---------------------------------------------------

  private def lobbyScreen(code: String): HtmlElement =
    // Refresh on mount so a deep-link / refresh into the lobby URL works,
    // and poll while we're on the screen so guest joins / host starts
    // appear without a page reload. Cleared on unmount via the returned
    // handle.
    var pollHandle: Int = 0
    Components.screenLayout("lobby")(
      onMountCallback { _ =>
        refreshLobbyByCode(code)
        pollHandle = dom.window.setInterval(
          () => refreshLobbyByCode(code),
          2000.0
        )
      },
      onUnmountCallback { _ =>
        if pollHandle != 0 then dom.window.clearInterval(pollHandle)
        pollHandle = 0
      },
      Components.titleCard(
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Lobby")
      ),
      Components.contentCard(
        child <-- currentLobbyVar.signal.map {
          case None    => lobbyLoadingBody(code)
          case Some(l) => lobbyDetailsBody(l)
        }
      )
    )

  private def lobbyLoadingBody(code: String): HtmlElement =
    p(className := "lobby-blurb", s"Loading lobby $code…")

  private def lobbyDetailsBody(l: LobbyJson): HtmlElement =
    div(
      className := "lobby-details flex flex-col gap-2",
      Components.formRow("Invite code")(
        div(
          className := "flex flex-row items-center gap-2",
          span(className := "lobby-code font-press", l.inviteCode),
          Components.iconButton("⧉") { _ =>
            dom.window.navigator.clipboard.writeText(l.inviteCode)
            showToast("Invite code copied")
          }
        )
      ),
      Components.formRow("Invite link")(
        span(
          className := "lobby-link font-press break-all",
          s"${dom.window.location.origin}/#lobby/${l.inviteCode}"
        )
      ),
      Components.formRow("Status")(span(className := "lobby-value", l.status)),
      Components.formRow("Host")(span(className := "lobby-value", l.hostNickname)),
      Components.formRow("Guest")(
        span(
          className := "lobby-value",
          l.guestNickname.fold("(waiting…)")(identity)
        )
      ),
      Components.formRow("Visibility")(
        span(className := "lobby-value", l.visibility)
      ),
      Components.formRow("Allow undo")(
        span(className := "lobby-value", l.allowUndo.toString)
      ),
      Components.formRow("Spectators")(
        span(
          className := "lobby-value",
          if l.allowSpectate then s"allowed (limit ${l.spectatorLimit})"
          else "not allowed"
        )
      ),
      // Host-only start button, only when the lobby is Full.
      if l.hostSessionId == sessionId && l.status == "Full" then
        Components.ctaButton("Start game") { _ => startHostedGame(l) }
      else span(),
      // Once the host has started the game, both sides show a link to the board.
      if l.status == "Started" && l.gameId.isDefined then
        Components.linkAnchor("Game started — go to board", s"#game/${l.gameId.get}")
      else span()
    )

  private def refreshLobbyByCode(code: String): Unit =
    fetchJson("GET", s"$lobbyBaseUrl/lobbies/by-code/$code", None).onComplete {
      case scala.util.Success(raw) =>
        parseLobbyJson(raw).foreach { l =>
          currentLobbyVar.set(Some(l))
          // If the host pressed Start while we were watching, jump straight
          // to the game screen. Both host (still here from create) and
          // guest (still here from join) get auto-routed via this code path.
          if l.status == "Started" && l.gameId.isDefined then
            navigate(Screen.Game(l.gameId.get))
        }
      case scala.util.Failure(_) => ()  // swallow — screen stays on "Loading…"
    }

  /** Host clicks "Start game". Create the game on the gateway, then ask
    * the lobby-service to mark the lobby as Started — which in turn
    * notifies the gateway to swap the role registry to host+guest.
    */
  private def startHostedGame(l: LobbyJson): Unit =
    postCreateGameClient((sessionId, CreateGameRequest(None))).foreach {
      case Right(snapshot) =>
        val payload = js.JSON.stringify(
          js.Dynamic.literal(gameId = snapshot.id)
        )
        fetchJson(
          "POST",
          s"$lobbyBaseUrl/lobbies/${l.id}/start",
          Some(payload)
        ).onComplete {
          case scala.util.Success(_) =>
            navigate(Screen.Game(snapshot.id))
          case scala.util.Failure(err) =>
            showToast(s"Start failed: ${err.getMessage}")
        }
      case Left(err) => showToast(err.error)
    }

  // -- Shared helpers -------------------------------------------------------


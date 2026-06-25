package chess.webui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sttp.client3.FetchBackend
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.json.*

import chess.api.{AnalyzeRequestDto, BoardStateDto, CreateGameRequest, Endpoints, ErrorDto, ExportResponse, GameAnalysisDto, GameStatusDto, MoveEntryDto, MoveRequest, OngoingGame, ReplayFrame, ReplayResponse, SquareDto, StackInfoResponse, StateResponse, VsBotSettings}
import chess.webui.components.{Components, ModalRegistry}

object Main:

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), App())

  private case class PendingPromotion(from: String, to: String)

  // --------------------------------------------------------------------------
  // Reactive state
  // --------------------------------------------------------------------------

  private val stateVar: Var[Option[BoardStateDto]] = Var(None)

  // -- Replay (review a finished game move-by-move) ---------------------------
  // Fetched once when a game completes (GET /api/games/{id}/replay), cached so
  // scrubbing is instant + offline. `activePlyVar` is the shown frame index
  // (0 = initial position … N = final), default N. See docs/replay-plan.md.
  private val replayFramesVar: Var[Vector[ReplayFrame]] = Var(Vector.empty)
  private val activePlyVar: Var[Int]                    = Var(0)

  // -- Analysis (post-game move quality) -------------------------------------
  // Opt-in: the engine rates the game (POST /api/analyze with the game PGN)
  // only when the player presses "Analyze game" on the end-of-game banner — it
  // is a deep per-move search, so we don't spend it unless asked. The eval bar
  // + move-detail panel read this alongside `activePlyVar`, so the analysis
  // scrubs in lock-step with the replay board.
  private val analysisVar: Var[Option[GameAnalysisDto]] = Var(None)
  // True from the moment "Analyze game" is pressed until the result lands (or
  // the request fails) — drives the button's "Analyzing…" / disabled state.
  private val analyzeRequestedVar: Var[Boolean] = Var(false)

  /** The position shown on the board: the live `stateVar`, except while
    * replaying a finished game, when it's the selected historical frame. Only
    * the board + captured pieces follow this; the move log + result card stay on
    * the live `stateVar` (so the result never reads "playing" mid-replay). */
  private val boardViewSignal: Signal[Option[BoardStateDto]] =
    stateVar.signal
      .combineWith(replayFramesVar.signal)
      .combineWith(activePlyVar.signal)
      .map { case (live, frames, ply) =>
        if frames.isEmpty then live
        else frames.lift(ply).map(_.boardState).orElse(live)
      }

  /** While time-travelling, the from/to squares of the move that produced the
    * shown frame (frame `ply-1` → `ply`), so the moved piece is highlighted.
    * Empty when not replaying or on the initial position (ply 0). */
  private val replayMovedSignal: Signal[Set[String]] =
    replayFramesVar.signal
      .combineWith(activePlyVar.signal)
      .map { (frames, ply) =>
        if frames.isEmpty || ply <= 0 then Set.empty
        else
          (frames.lift(ply - 1), frames.lift(ply)) match
            case (Some(prev), Some(cur)) =>
              Logic.movedSquares(prev.boardState, cur.boardState)
            case _ => Set.empty
      }

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
  // Cancelable handle for the active toast's auto-dismiss timer, so a
  // fresh toast (or a manual dismiss) clears the previous countdown
  // instead of letting a stale timer wipe a newer message early.
  private var toastTimer: Option[Int] = None
  // Live spectator count for the active game, pushed over SSE as the
  // `spectators` event and shown in the header eye badge.
  private val spectatorCountVar: Var[Int] = Var(0)

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
    // Read-only spectator view of a mirrored game (Lichess bot-game or a
    // lobby game watched as a non-player). Same board, no input.
    case Watch(gameId: String)
    // Unified list of ongoing games to watch (PvP / PvBot / Lichess /
    // tournament), and the list of NowChess tournaments to enter piChess into.
    case Spectate
    case Tournaments
    case Settings
    case Help
    case Analytics

  private val currentScreenVar: Var[Screen] = Var(Screen.Start)

  /** Translate `dom.window.location.hash` into a `Screen`. Anything we
    * don't recognise falls back to Start.
    */
  private def parseHash(raw: String): Screen =
    val stripped = raw.stripPrefix("#")
    stripped match
      case "" | "/"             => Screen.Start
      case "new"                => Screen.NewGameMenu
      case "join"               => Screen.Join
      case "spectate"           => Screen.Spectate
      case "tournaments"        => Screen.Tournaments
      case "settings"           => Screen.Settings
      case "help"               => Screen.Help
      case "analytics"          => Screen.Analytics
      case s"lobby/$c"          => Screen.Lobby(c)
      case s"watch/$id"         => Screen.Watch(id)
      case s"game/$id"          => Screen.Game(id)
      case _                    => Screen.Start

  private def hashFor(screen: Screen): String = screen match
    case Screen.Start          => ""
    case Screen.NewGameMenu    => "#new"
    case Screen.Join           => "#join"
    case Screen.Spectate       => "#spectate"
    case Screen.Tournaments    => "#tournaments"
    case Screen.Lobby(code)    => s"#lobby/$code"
    case Screen.Game(id)       => s"#game/$id"
    case Screen.Watch(id)      => s"#watch/$id"
    case Screen.Settings       => "#settings"
    case Screen.Help           => "#help"
    case Screen.Analytics      => "#analytics"

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

  // Vs-bot game-creation modal state. `playerSide` is the colour the
  // user chooses to play; the bot takes the opposite (so botSide on
  // the wire is the negation of this). Difficulty is a free-form
  // string matching `chess.bot.engine.Difficulty` enum values — the
  // server validates. `allowUndo` is a hint to the client to grey out
  // the undo/redo controls; the server doesn't enforce it.
  private val vsBotPlayerSideVar: Var[String] = Var("white")
  private val vsBotDifficultyVar: Var[String] = Var("Medium")
  private val vsBotAllowUndoVar: Var[Boolean] = Var(true)

  /** Whether the *active* game permits undo/redo. Set at each game-start
    * path (true for local games; the vs-bot / host setting otherwise); the
    * board controls strike the Undo/Redo post-its when this is false (§5.9). */
  private val currentAllowUndoVar: Var[Boolean] = Var(true)

  /** Whether the active game is a solo local / vs-bot game the player fully
    * controls (as opposed to a multiplayer lobby game). Drives whether the
    * board's Load action — which rewrites the position — is offered: you can't
    * unilaterally load a position into a shared two-player game. Set at the
    * create / lobby-entry choke points; defaults true (a bare `#game/<id>` deep
    * link is almost always one's own local/bot game). */
  private val currentGameIsLocalVar: Var[Boolean] = Var(true)

  /** The board-screen "who vs whom" title for the *active* game. Set by
    * `enterGame` from `pendingTitleVar` (staged by whichever flow knew the
    * matchup) or, for a lobby game, derived from `currentLobbyVar`. `None`
    * renders the generic "White vs Black" fallback. */
  private val gameTitleVar: Var[Option[Logic.GameTitle]] = Var(None)

  /** Title staged for the NEXT game we navigate into, consumed once by
    * `enterGame`. Decouples "the create / spectate flow knows the players"
    * from "the router actually enters the game", and self-clears so a later
    * deep-link entry falls back to generic instead of a stale title. */
  private val pendingTitleVar: Var[Option[Logic.GameTitle]] = Var(None)

  /** Optional FEN/PGN/JSON to start a fresh local / vs-bot game FROM, set on
    * the New Game screen's import field (local + bot modes only). */
  private val newGameImportVar: Var[String] = Var("")

  /** True once the active game has ended (checkmate / draw / resignation).
    * Drives the §5.10 end-screen: gates the move input + Draw / Forfeit and
    * triggers the result card. Lazy so it doesn't touch `stateVar` during
    * field init. */
  private lazy val gameOverSignal: Signal[Boolean] =
    stateVar.signal.map(_.exists(_.status.kind != "playing"))

  /** The result card auto-shows when the game ends and hides once dismissed.
    * The dismiss flag is reset when the game returns to "playing" (new game /
    * undo) by an observer in App() so the card shows again next time (§5.10). */
  private val resultDismissedVar: Var[Boolean] = Var(false)
  private lazy val resultOpenSignal: Signal[Boolean] =
    gameOverSignal
      .combineWith(resultDismissedVar.signal)
      .map { case (over, dismissed) => over && !dismissed }
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
          // Both Game and Watch open the SSE feed for an id; Watch just
          // renders it read-only. `enterGame` is idempotent + reusable.
          case Screen.Game(id)  => enterGame(id, spectator = false)
          case Screen.Watch(id) => enterGame(id, spectator = true)
          case _                => disconnectEvents()
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
          ModalRegistry.register("result",  resultOpenSignal)
          // Reset the result card's dismiss flag whenever the game returns to
          // "playing" (a new game, or an undo past the end) so the card shows
          // again the next time a game ends (§5.10).
          gameOverSignal.changes.filter(!_).foreach { _ =>
            resultDismissedVar.set(false)
            // Back to "playing" (new game / undo past the end): drop the replay
            // cache so the move log stops being clickable, and clear analysis.
            replayFramesVar.set(Vector.empty)
            activePlyVar.set(0)
            analysisVar.set(None)
            analyzeRequestedVar.set(false)
          }
          // On completion, pull the full position history once so the move log
          // becomes a clickable replay scrubber (board time-travel). Engine
          // analysis is opt-in (the "Analyze game" banner button), not fetched
          // here — it's a deep search we only spend when the player asks.
          gameOverSignal.changes.filter(identity).foreach { _ =>
            gameIdVar.now().foreach(fetchReplay)
          }
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
    case Screen.Spectate        => spectateScreen()
    case Screen.Tournaments     => tournamentsScreen()
    case Screen.Lobby(code)     => lobbyScreen(code)
    case Screen.Game(_)         => mainUi()
    case Screen.Watch(_)        => spectatorUi()
    case Screen.Settings        => settingsScreen()
    case Screen.Help            => helpScreen()
    case Screen.Analytics       => analyticsScreen()

  /** Side-effect when entering a Game screen: ensure gameIdVar is set,
    * pull current state, (re)connect SSE for that id. Idempotent — a
    * navigation that lands on the same id won't double-subscribe because
    * `connectEvents` closes any existing stream first.
    */
  private def enterGame(id: String, spectator: Boolean): Unit =
    gameIdVar.set(Some(id))
    // Adopt the lobby's allowUndo ONLY when we actually came through a lobby
    // (hosted / joined games). For vs-bot / local games the create path already
    // set currentAllowUndoVar; this fires on navigate right after create, so a
    // `getOrElse(true)` here would clobber a vs-bot "Allow undo: off" back on.
    currentLobbyVar.now().foreach(l => currentAllowUndoVar.set(l.allowUndo))
    // Resolve the board title: a flow that knew the matchup staged it; else a
    // multiplayer lobby game takes its roster from the lobby (host = White by
    // convention); else `None` → the generic "White vs Black" fallback. Always
    // consume the pending slot so the next entry can't inherit a stale title.
    gameTitleVar.set(
      pendingTitleVar
        .now()
        .orElse(if currentGameIsLocalVar.now() then None else lobbyTitle())
    )
    pendingTitleVar.set(None)
    getStateClient((id, None)).foreach(handleStateResult)
    connectEvents(id, spectator)

  /** A lobby game's title from `currentLobbyVar`: host plays White, guest
    * Black (the seat-assignment convention). `None` when no lobby is active. */
  private def lobbyTitle(): Option[Logic.GameTitle] =
    currentLobbyVar
      .now()
      .map(l =>
        Logic.GameTitle.players(l.hostNickname, l.guestNickname.toOption.getOrElse(""))
      )

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
      resultCard(),
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
  // Spectator (read-only) view — reused by the Lichess watch screen AND by
  // lobby spectators. Same board, fed by the same SSE feed, but with no
  // input handlers and none of the post-it action buttons.
  // --------------------------------------------------------------------------

  private def spectatorUi(): HtmlElement =
    div(
      className := "app-shell",
      header(),
      spectatorBody(),
      // Spectators get the same end-of-game card when the watched game ends —
      // but without "New Game" (they're not a player).
      resultCard(spectator = true)
    )

  private def spectatorBody(): HtmlElement =
    div(
      className := "app",
      spectatorBoardArea(),
      // Move log only — no game-state post-it (New / Load / Draw / Forfeit)
      // and no move-input field (the board is read-only).
      div(
        className := "sidebar",
        moveLogContainer(showInput = false)
      )
    )

  /** Board area without the board post-it: the paper sheet, the status
    * banner, the read-only board and captured pile — nothing actionable. */
  private def spectatorBoardArea(): HtmlElement =
    div(
      className := "board-area",
      div(
        className := "board-paper",
        paperLayer(crumpled = true),
        evalBar(),
        gameTitle(),
        statusIndicator(),
        div(
          className := "board-row",
          div(
            className := "board-wrapper",
            rankLabels(),
            board(readOnly = true),
            fileLabels()
          ),
          capturedPile()
        )
      )
    )

  /** Whether the server has Lichess configured (a token), injected as the
    * `pichess-lichess` meta by HtmlPage. Lichess is an opt-in external
    * integration, so its UI (the "Challenge a bot" link) is hidden when off. */
  private val lichessEnabled: Boolean =
    Option(dom.document.querySelector("meta[name='pichess-lichess']"))
      .flatMap(e => Option(e.getAttribute("content")))
      .contains("true")

  /** Kick off a live Lichess bot-game on the server, then navigate to the
    * read-only spectator view of its mirror game. */
  private def startLichessWatch(): Unit =
    showToast("Starting a Lichess bot-game…")
    fetchJson("POST", "/lichess/games", None).onComplete {
      case scala.util.Success(raw) =>
        try
          val obj      = js.JSON.parse(raw).asInstanceOf[js.Dynamic]
          val mirrorId = obj.mirrorId.asInstanceOf[String]
          dismissToast()
          navigate(Screen.Watch(mirrorId))
        catch
          case _: Throwable =>
            showToast("Couldn't start a game (bad response).")
      case scala.util.Failure(err) =>
        // A 404 here means the /lichess routes aren't mounted — i.e. no Lichess
        // token is configured on this server (it's an opt-in external
        // integration), so surface that rather than a raw "HTTP 404".
        val msg = Option(err.getMessage).getOrElse("")
        if msg.contains("404") then
          showToast("Lichess play isn't configured on this server.")
        else showToast(s"Couldn't start a game: $msg")
    }

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
    spectatorHeaderWidget(),
    themeToggleButton(),
    a(
      className := "header-link",
      href := "#help",
      "Help"
    )
  )

  /** Header control: an eye glyph + live spectator count for the current
    * game. Read-only — there's no share popover; spectators discover games
    * to watch through the Spectate menu. Only meaningful on the Game / Watch
    * screens, which are the only screens that render the header. */
  private def spectatorHeaderWidget(): HtmlElement =
    div(
      className := "spectator-widget",
      span(
        className := "spectator-eye",
        title := "Spectators watching",
        icon("spectate"),
        span(
          className := "spectator-count",
          child.text <-- spectatorCountVar.signal.map(_.toString)
        )
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
        evalBar(),
        gameTitle(),
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
          actionButton("undo", "Undo", modifier = "", () => postUndo(),
                       disabled = currentAllowUndoVar.signal.map(!_)),
          actionButton("redo", "Redo", modifier = "", () => postRedo(),
                       disabled = currentAllowUndoVar.signal.map(!_)),
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
        // Load rewrites the board position, which only makes sense for a solo
        // local / vs-bot game — struck out (§5.9) on a multiplayer lobby game.
        actionButton("load",    "Load",     "action-load",    () => loadOpenVar.set(true),
                     disabled = currentGameIsLocalVar.signal.map(!_)),
        actionButton("draw",    "Draw",     "action-draw",    () => askConfirm(confirmDraw),    disabled = gameOverSignal),
        actionButton("forfeit", "Forfeit",  "action-forfeit", () => askConfirm(confirmForfeit), disabled = gameOverSignal)
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
    val signal = boardViewSignal.combineWith(flippedVar.signal).map {
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
        // Each section collapses to one little stack per piece TYPE (the
        // pieces arrive value-sorted, so same types are already adjacent), with
        // a handwritten count chip — so the gutter height is bounded by the 5
        // capturable types, not by how many were taken (it no longer stretches
        // the board on a heavy endgame).
        List(
          div(
            className := "captured-section captured-section-top",
            groupCaptures(topPieces).map { case (n, c) =>
              renderCapturedStack(n, topColor, c)
            }
          ),
          div(
            className := "captured-section captured-section-bottom",
            groupCaptures(bottomPieces).map { case (n, c) =>
              renderCapturedStack(n, bottomColor, c)
            }
          )
        )
      }
    )

  /** Collapse a value-sorted run of piece names into `(name, count)` groups,
    * preserving order (same types are already consecutive). */
  private def groupCaptures(pieces: List[String]): List[(String, Int)] =
    pieces
      .foldLeft(List.empty[(String, Int)]) { (acc, name) =>
        acc match
          case (n, c) :: tail if n == name => (n, c + 1) :: tail
          case _                           => (name, 1) :: acc
      }
      .reverse

  /** One captured-piece stack: up to three overlapping stickers (deterministic
    * per-index tilt — NOT RNG, so it stays put across the per-move re-render)
    * plus a handwritten count chip when 2+ were taken. */
  private def renderCapturedStack(
      name: String,
      color: String,
      count: Int
  ): HtmlElement =
    span(
      className := "captured-stack",
      (0 until math.min(count, 3)).map { i =>
        span(
          className := s"captured-piece $color-piece",
          styleAttr := s"bottom: ${i * 0.16}rem; transform: rotate(${capturedTilt(i)}deg);",
          pieceSvg(name)
        )
      },
      if count >= 2 then span(className := "captured-count", count.toString)
      else emptyNode
    )

  private val capturedTilts = Array(-4, 4, -2)
  private def capturedTilt(i: Int): Int =
    capturedTilts(i % capturedTilts.length)

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

  private def board(readOnly: Boolean = false): HtmlElement =
    div(
      className := "board",
      children <-- boardViewSignal
        .combineWith(flippedVar.signal)
        .combineWith(gameOverSignal)
        .map {
          case (None, _, _) => List.empty
          case (Some(s), flipped, over) =>
            // A finished game is read-only — viewing a past ply (or the final
            // position) is review, not play; this also stops dragging on a
            // completed board.
            val ro      = readOnly || over
            val squares = if flipped then s.squares.reverse else s.squares
            squares.map(renderSquare(s, _, ro))
        }
    )

  private def renderSquare(
      state: BoardStateDto,
      sq: SquareDto,
      readOnly: Boolean
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
      // Replay time-travel: the from/to of the move that reached the shown
      // frame, in the move-preview blue.
      cls("is-replay-move") <-- replayMovedSignal
        .map(_.contains(sq.pos))
        .distinct,
      cls("is-attacker") <-- attackersVar.signal
        .map(_.contains(sq.pos))
        .distinct,
      // Promotion: solid green ring on the pawn's origin square, dashed green
      // ring on the square it's about to promote onto (akin to threat/attacker).
      cls("is-promoting") <-- pendingPromotionVar.signal
        .map(_.exists(_.from == sq.pos))
        .distinct,
      cls("is-promo-dest") <-- pendingPromotionVar.signal
        .map(_.exists(_.to == sq.pos))
        .distinct,
      // Spectator boards are read-only: no click-to-move, no drag.
      if readOnly then emptyMod else onClick --> { _ => onSquareClick(sq) },
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
          if readOnly then emptyMod
          else
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

  /** Win-probability eval bar for the position currently shown (live or the
    * replay-scrubbed ply). White's share is the move's white-relative win%;
    * the centre label is the eval (`+1.5` / `#`). Renders only once the engine
    * analysis has arrived; tracks `activePlyVar` so it scrubs with the board. */
  private def evalBar(): HtmlElement =
    div(
      className := "eval-bar",
      child <-- analysisVar.signal
        .combineWith(activePlyVar.signal)
        .map { (analysis, ply) =>
          analysis match
            case None => emptyNode
            case Some(_) =>
              val mv = Logic.analysisAtPly(analysis, ply)
              val whitePct = mv.map(m => Logic.evalBarWhitePct(m.winPct)).getOrElse(50.0)
              val label = mv.map(m => Logic.evalText(m.evalCp)).getOrElse("")
              div(
                className := "eval-bar-row",
                div(
                  className := "eval-bar-track",
                  div(
                    className := "eval-bar-white",
                    styleAttr := s"width: $whitePct%"
                  )
                ),
                span(className := "eval-bar-label", label)
              )
        }
    )

  /** Named opening (ECO + name) and per-side accuracy, shown once analysis
    * arrives. A static summary of the whole game (not ply-keyed). */
  private def openingLabel(): HtmlElement =
    div(
      className := "opening-postit-wrap",
      child <-- analysisVar.signal.map {
        case None => emptyNode
        case Some(a) =>
          // A little post-it slapped on the top-right corner of the move-log
          // panel (same vocabulary as the board post-it), carrying the named
          // opening + per-side accuracy. Content flex-wraps so a long opening
          // name stacks instead of overflowing the sticky.
          div(
            className := "post-it-shadow opening-post-it",
            div(
              className := "post-it-card opening-post-it-card",
              span(className := "opening-postit-name", Logic.openingLabel(a.opening)),
              span(
                className := "opening-postit-acc",
                s"♙ ${Logic.accuracyText(a.accuracyWhite)} · ♟ ${Logic.accuracyText(a.accuracyBlack)}"
              )
            )
          )
      }
    )

  /** Detail for the move that produced the shown ply: quality class, eval, the
    * engine's best move, and accuracy — empty until analysis arrives / on the
    * initial position. Keyed on `activePlyVar`, so clicking a move (which the
    * replay scrubber already does) also updates this panel. */
  private def analysisDetail(): HtmlElement =
    div(
      className := "analysis-detail",
      child <-- analysisVar.signal
        .combineWith(activePlyVar.signal)
        .map { (analysis, ply) =>
          Logic.analysisAtPly(analysis, ply) match
            case None => emptyNode
            case Some(m) =>
              // The move-quality bucket ("blunder", "best", …) drives both the
              // doodle icon and the colour; works for Book/Best too (which have
              // no NAG glyph).
              val cls = m.moveClass.toLowerCase
              div(
                className := s"analysis-detail-inner quality-$cls",
                span(
                  className := s"icon analysis-quality-icon icon-quality-$cls",
                  aria.hidden := true
                ),
                span(className := "analysis-class", m.moveClass),
                span(className := "analysis-eval", Logic.evalText(m.evalCp)),
                span(className := "analysis-best", s"best ${m.bestMove}")
              )
        }
    )

  private def statusIndicator(): HtmlElement =
    div(
      child <-- stateVar.signal.map {
        case None => emptyNode
        case Some(s) =>
          s.status.kind match
            case "checkmate" | "draw" | "resignation" => endStatePanel(s.status)
            case _                                    => turnIndicator(s)
      }
    )

  /** The end-of-game row pasted across the top of the board: the verdict
    * banner, then a doodle arrow pointing at the "Analyze game" CTA. The arrow
    * + CTA drop away once the analysis has come back (it then lives in the
    * move-log sidebar, so the prompt has done its job). */
  private def endStatePanel(status: GameStatusDto): HtmlElement =
    div(
      className := "end-state-wrap",
      // Crown to the left of the line — always visible (sibling of `.end-state`,
      // so it survives the analysis fade) and re-opens the game-end modal.
      button(
        typ := "button",
        className := "end-state-crown",
        aria.label := "Show game result",
        onClick --> { _ => resultDismissedVar.set(false) },
        icon("crown")
      ),
      // The verdict banner + "Analyze game" prompt. Once analysis arrives it
      // fades to opacity 0 (but keeps its box) and the per-move analysis detail
      // is laid over the same spot — so the status line shows the move quality
      // while scrubbing, with no layout shift.
      div(
        className := "end-state",
        cls("is-faded") <-- analysisVar.signal.map(_.isDefined),
        resultBanner(status),
        child <-- analysisVar.signal.map {
          case Some(_) => emptyNode
          case None    => analyzePrompt()
        }
      ),
      analysisDetail()
    )

  /** Arrow doodle + the opt-in "Analyze game" CTA. The button is disabled while
    * a request is in flight ("Analyzing…"); on success the panel hides, on
    * failure it resets so the player can retry. */
  private def analyzePrompt(): HtmlElement =
    div(
      className := "analyze-prompt",
      div(className := "analyze-arrow", aria.hidden := true),
      button(
        typ := "button",
        className := "btn-link analyze-cta",
        disabled <-- analyzeRequestedVar.signal,
        child.text <-- analyzeRequestedVar.signal.map(r =>
          if r then "Analyzing…" else "Analyze game"
        ),
        onClick --> { _ =>
          gameIdVar.now().foreach { id =>
            analyzeRequestedVar.set(true)
            requestAnalysis(id)
          }
        }
      )
    )

  // Status panel-header — rendered inside .board-paper so it sits on the
  // same sheet as the board. No own paperLayer (board-paper provides it);
  // styled as an inline label rather than a standalone paper note.
  private def turnIndicator(s: BoardStateDto): HtmlElement =
    val name = if s.activeColor == "white" then "White" else "Black"
    div(
      className := "turn-indicator",
      colorDot(s.activeColor),
      span(s"$name to move")
    )

  /** The small filled circle marking a chess side's colour — the cross-hatched
    * marker-on-paper disc shared by the "X to move" status line and the
    * game-title header. `color` is "white" / "black"; styling is in
    * `.color-dot` (style.css). */
  private def colorDot(color: String): HtmlElement =
    div(className := s"color-dot $color")

  /** Board-screen title: "(○) White ─ vs ─ (●) Black", each name behind its
    * colour dot. Reactive on `gameTitleVar`; an unset title (deep link, raw
    * `#game/<id>`) falls back to the generic colour words. */
  private def gameTitle(): HtmlElement =
    div(
      className := "game-title",
      children <-- gameTitleVar.signal.map { t0 =>
        val t = t0.getOrElse(Logic.GameTitle.local)
        List(
          titlePlayer("white", t.white),
          span(className := "game-title-vs", "vs"),
          titlePlayer("black", t.black)
        )
      }
    )

  private def titlePlayer(color: String, name: String): HtmlElement =
    span(
      className := "game-title-player",
      colorDot(color),
      span(className := "game-title-name", name)
    )

  /** The verdict as a handwritten sentence with the winning colour and the
    * end-condition pasted in as newspaper cuttings — e.g. "[Black] wins by
    * [checkmate!]" or "[Draw] by [stalemate]". Shared by the in-board banner
    * and the result modal's subtitle so both read identically: cuttings for
    * the nouns, handwriting for the connective. `heading` switches the cuttings
    * to headline size (the banner); `verb` is the tense ("wins" / "won"). */
  private def verdictParts(
      status: GameStatusDto,
      clipClass: String,
      connClass: String,
      heading: Boolean,
      verb: String
  ): List[HtmlElement] =
    def clip(text: String): HtmlElement =
      Components.newsprintClip(clipClass, heading = heading)(text)
    def conn(text: String): HtmlElement =
      span(className := connClass, text)
    def winnerName: String = status.winner.map(capitalize).getOrElse("Someone")
    status.kind match
      case "checkmate" =>
        List(clip(winnerName), conn(s"$verb by"), clip("checkmate!"))
      case "resignation" =>
        List(clip(winnerName), conn(s"$verb by"), clip("resignation"))
      case "draw" =>
        val reason =
          status.reason.map(Logic.humanizeDrawReason).getOrElse("agreement")
        List(clip("Draw"), conn("by"), clip(reason))
      case _ => List.empty

  /** The in-board verdict banner — same clipping/handwriting pattern as the
    * result modal's subtitle, at headline size. */
  private def resultBanner(status: GameStatusDto): HtmlElement =
    val kind = if status.kind == "draw" then "draw" else "win"
    div(
      className := s"banner $kind",
      verdictParts(
        status,
        clipClass = "banner-clip",
        connClass = "banner-conn",
        heading = true,
        verb = "wins"
      )
    )

  private def capitalize(s: String): String =
    if s.isEmpty then s else s"${s.head.toUpper}${s.tail}"

  private def moveLogContainer(showInput: Boolean = true): HtmlElement =
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
          Components.newsprintClip()("Moves")
        ),
        // Named opening + per-side accuracy, shown once the analysis arrives.
        openingLabel(),
        div(
          // Stable outer — OS wraps this on mount. Laminar's `children <--`
          // can't go on this element because OS rewrites the DOM under it
          // (.os-viewport > .os-padding > .os-contents), and Laminar would
          // start appending new children outside OS's wrapper while the
          // initial ones stay nested inside (the duplicate "no moves yet"
          // bug). The dynamic content lives on .move-log-inner instead;
          // OS doesn't touch that node, so Laminar updates it freely.
          className := "move-log",
          withCustomScrollbar,
          div(
            className := "move-log-inner",
            // `.replayable` (game over) turns on the clickable-move affordance
            // (pointer cursor + hover underline) via CSS.
            cls("replayable") <-- gameOverSignal,
            children <-- stateVar.signal.map {
              case None    => List.empty
              case Some(s) => renderMoveLog(s.moveLog)
            }
          )
        ),
        // (Per-move analysis detail now lives over the board's end-state line,
        // not here — see `endStatePanel`.)
        // Move input lives BELOW the scrolling log, on the same paper but
        // outside the OS-managed scroll viewport. Player view only — a
        // spectator's read-only board has no move entry, so it's dropped
        // entirely there (showInput = false).
        if showInput then moveInputForm() else emptyNode
      )
    )

  private def moveInputForm(): HtmlElement =
    form(
      idAttr := "moveForm",
      // Struck out once the game ends (§5.9/§5.10): `.is-struck` draws the
      // line + blocks pointer events; the submit guard below stops Enter.
      className <-- gameOverSignal.map(o => if o then "is-struck" else ""),
      onSubmit.preventDefault --> { _ =>
        val v = moveInputVar.now().trim
        if v.nonEmpty && stateVar.now().exists(_.status.kind == "playing")
        then
          postMove(v)
          moveInputVar.set("")
      },
      // The wrapper carries the hand-drawn underline pseudo so the input
      // itself can stay borderless. `min-w-[8rem]` is the wrap-floor: when the
      // sidebar narrows, the input shrinks to that width before the form wraps.
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
      // Icon-only submit — matches the visual vocabulary of Undo / Redo / Flip
      // in the post-its. The neon-orange marker stripe on hover
      // (--marker-yellow) ties it to the heading marker colour family.
      button(
        className := "post-it-action icon-only action-move",
        aria.label := "Submit move",
        tpe := "submit",
        icon("move")
      )
    )

  private def renderMoveLog(moves: List[MoveEntryDto]): List[HtmlElement] =
    if moves.isEmpty then
      List(div(className := "move-log-empty", "No moves yet"))
    else
      Logic.groupMovesByTwo(moves).map { case (num, white, blackOpt) =>
        // Each SAN is its own little newspaper cutting (the reusable
        // `newsprintClip`), so the clipping hugs the move token — "Nf3" —
        // rather than the whole row sitting on one newsprint slab. The move
        // number stays as plain pencil text on the grid paper between them.
        val whiteIdx = (num - 1) * 2
        val cells = List(
          span(className := "move-number", s"$num."),
          moveCell(whiteIdx, white.san)
        ) ++ blackOpt.map(m => moveCell(whiteIdx + 1, m.san))
        div(className := "move-row", cells)
      }

  /** One clickable half-move cutting for replay. `i` is the flat half-move
    * index (0-based). Once a finished game's frames are loaded, clicking jumps
    * the board to the position after this move; the **active** move (the one
    * that produced the shown position) gets the emphatic underline and **later**
    * moves are muted — both reactive on `activePlyVar`, so a click restyles
    * without re-rendering the log. The click is a no-op while a game is still in
    * progress (no frames). */
  private def moveCell(i: Int, san: String): HtmlElement =
    val st = activePlyVar.signal.map(Logic.replayMoveState(i, _))
    // Per-move quality glyph (!!/!/?!/?/?? …) from the analysis, when present.
    val glyph: Signal[Option[String]] =
      analysisVar.signal.map(a => Logic.analysisForMove(a, i).flatMap(_.glyph))
    span(
      className := "move-cell-wrap",
      Components
        .newsprintClip("move-san")(san)
        .amend(
          cls := "move-cell",
          cls("is-active") <-- st.map(_._1),
          cls("is-future") <-- st.map(_._2),
          onClick --> { _ =>
            if replayFramesVar.now().nonEmpty then activePlyVar.set(i + 1)
          }
        ),
      child <-- glyph.map {
        case Some(g) =>
          span(className := s"move-glyph move-glyph-${Logic.glyphClass(Some(g))}", g)
        case None => emptyNode
      }
    )

  /** A doodle icon inlined as a `<span>` whose backing CSS rule (`.icon-…`)
    * sets `--icon-url` and the masking machinery — keeps Laminar-side code
    * declarative ("show the undo glyph") and the styling decisions (size,
    * colour, mask-mode) centralised in style.css.
    */
  private def icon(name: String): HtmlElement =
    span(className := s"icon icon-$name", aria.hidden := true)

  /** Skin an element's scrollbar with the hand-drawn OverlayScrollbars theme
    * (os-theme-pichess) — vendored locally + guarded, so a missing OS global
    * just leaves the native scrollbar. Apply to any scroll container
    * (overflow: auto/scroll). NOTE: OS rewrites the element's content into its
    * own wrappers, so never put a Laminar `children <--` directly on the same
    * node — keep dynamic content one level deeper (see the move-log). */
  private def withCustomScrollbar =
    onMountCallback { ctx =>
      val osg = js.Dynamic.global.OverlayScrollbarsGlobal
      if !js.isUndefined(osg) && osg != null then
        osg.OverlayScrollbars(
          ctx.thisNode.ref,
          js.Dynamic.literal(
            // Vertical-only: every scroll container here (move log, modal
            // bodies) wraps its content; horizontal "scroll" only ever appears
            // from a decorative overhang (e.g. the result modal's toppled-king
            // transform), so clip it rather than show a stray h-scrollbar.
            overflow =
              js.Dynamic.literal(x = "hidden", y = "scroll"),
            scrollbars = js.Dynamic.literal(theme = "os-theme-pichess")
          )
        )
    }

  /** Cap a reactive ledger list (`scrapTable`) at a sane height and scroll
    * the overflow. Dropped straight into the tilted `.content-card`, a long
    * list (a full tournament's games, a busy lobby) grows the card tall
    * enough that its 1.1° tilt swings the base sideways — the axis-aligned
    * box widens and the panel slides off-screen. Capping + scrolling keeps
    * the card short whatever the row count. OS rewrites the DOM under the
    * skinned node, so the reactive child sits one level deeper on
    * `.ledger-scroll-inner` (see `withCustomScrollbar`). */
  private def ledgerScroll(content: Modifier[HtmlElement]*): HtmlElement =
    div(
      className := "ledger-scroll",
      withCustomScrollbar,
      div(className := "ledger-scroll-inner").amend(content*)
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
      action: () => Unit,
      disabled: Signal[Boolean] = Val(false)
  ): HtmlElement =
    val base = if modifier.isEmpty then "post-it-action"
               else s"post-it-action $modifier"
    button(
      // `.is-struck` (§5.9) strikes the button through when disabled; its
      // pointer-events:none also blocks the click, so no extra gating needed.
      className <-- disabled.map(d => if d then s"$base is-struck" else base),
      aria.disabled <-- disabled,
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
    destructive = true, // ends the game — red proceed, like Forfeit
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
      title := "Click to dismiss",
      onClick --> { _ => dismissToast() },
      child.text <-- toastVar.signal.map(_.getOrElse(""))
    )

  private def promotionOverlay(): HtmlElement =
    div(
      idAttr := "promotionOverlay",
      className <-- pendingPromotionVar.signal.map(p =>
        if p.isDefined then "promotion-overlay visible" else "promotion-overlay"
      ),
      // Click the scrim (anywhere but a piece) to cancel — the move isn't
      // committed until a piece is picked, so this just returns the pawn.
      onClick --> { _ => pendingPromotionVar.set(None) },
      // No paper panel: the target pieces float as stickers over a dimmed,
      // lightly-blurred board (the origin/destination squares are ringed
      // behind the scrim). The board context carries the layout, so the row
      // just centres.
      div(
        idAttr := "promotionDialog",
        className := "promotion-pieces",
        onClick.stopPropagation --> { _ => () },
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
            onClick.stopPropagation --> { _ =>
              pendingPromotionVar.set(None)
              postMove(s"${p.from} ${p.to}=$key")
            },
            // Card-style 3D tilt toward the cursor (§ promotion picker).
            onMouseMove --> { e => tiltTowardCursor(e) },
            onMouseLeave --> { e => resetTilt(e) },
            pieceSvg(name)
          )
        }

  /** Tilt a promotion sticker in 3D so it "faces" the cursor (the card-follows-
    * pointer effect). The shared `perspective` lives on `.promotion-pieces`;
    * here we just set the per-piece `rotateX/rotateY` from the cursor's offset
    * to the sticker centre (−1..1 on each axis), plus a small lift. Flip the
    * sign of either rotate term to reverse that axis. */
  private def tiltTowardCursor(e: dom.MouseEvent): Unit =
    val el   = e.currentTarget.asInstanceOf[dom.html.Element]
    val rect = el.getBoundingClientRect()
    val dx   = (e.clientX - (rect.left + rect.width / 2)) / (rect.width / 2)
    val dy   = (e.clientY - (rect.top + rect.height / 2)) / (rect.height / 2)
    val max  = 14.0
    el.style.transform =
      s"rotateY(${dx * max}deg) rotateX(${-dy * max}deg) scale(1.14)"

  private def resetTilt(e: dom.MouseEvent): Unit =
    e.currentTarget.asInstanceOf[dom.html.Element].style.transform = ""

  /** The canonical modal (design.md § modals). A blurred backdrop that
    * dismisses on outside-click, a torn-paper dialog with photo-corner tape,
    * and a fixed head / scrolling body / fixed footer split by hand-drawn
    * rules. Every dialog (load, confirm, export, result) is built from this so
    * the chrome stays consistent — one place to tune. `backdropExtra` is a
    * sibling of the dialog inside the backdrop (the result card's sticker
    * rain); pass `Nil` for any empty band. */
  private def modalShell(
      open: Signal[Boolean],
      onDismiss: () => Unit,
      dialogClass: String,
      head: Seq[Modifier[HtmlElement]],
      body: Seq[Modifier[HtmlElement]],
      footer: Seq[Modifier[HtmlElement]],
      backdropExtra: Modifier[HtmlElement] = emptyNode
  ): HtmlElement =
    div(
      className <-- open.map(o => if o then "modal visible" else "modal"),
      onClick --> { _ => onDismiss() },
      backdropExtra,
      div(
        className := s"modal-dialog $dialogClass",
        onClick.stopPropagation --> { _ => () },
        paperLayer(),
        Components.tapeCorners(),
        div(className := "modal-head").amend(head*),
        div(className := "modal-body", withCustomScrollbar).amend(body*),
        div(className := "modal-footer").amend(footer*)
      )
    )

  private def loadModal(): HtmlElement =
    modalShell(
      open = loadOpenVar.signal,
      onDismiss = () => loadOpenVar.set(false),
      dialogClass = "load-dialog",
      head = Seq(h2(Components.newsprintClip(heading = true)("Load Game"))),
      body = Seq(
        p("Paste FEN, PGN, or JSON — the format is auto-detected."),
        // Hand-drawn frame on the grid paper (.load-field) wrapping a
        // transparent, handwritten-font textarea (§ paper input convention).
        div(
          className := "load-field",
          textArea(
            className := "load-input",
            rows := 8,
            placeholder := "rnbqkbnr/pppppppp/8/...  or  1. e4 e5 2. Nf3 ...",
            spellCheck := false,
            controlled(
              value <-- loadInputVar.signal,
              onInput.mapToValue --> loadInputVar
            )
          )
        )
      ),
      footer = Seq(
        Components.linkButton("Cancel") { _ => loadOpenVar.set(false) },
        Components.linkButton("Load", extraClass = "marker-green") { _ =>
          val raw = loadInputVar.now().trim
          if raw.nonEmpty then
            postLoad(raw)
            loadInputVar.set("")
            loadOpenVar.set(false)
        }
      )
    )

  private def confirmModal(): HtmlElement =
    modalShell(
      open = confirmVar.signal.map(_.isDefined),
      onDismiss = () => confirmVar.set(None),
      dialogClass = "confirm-dialog",
      head = Seq(
        h2(
          Components.newsprintClip(heading = true)(
            child.text <-- confirmVar.signal.map(_.map(_.title).getOrElse(""))
          )
        )
      ),
      body = Seq(
        p(child.text <-- confirmVar.signal.map(_.map(_.message).getOrElse("")))
      ),
      footer = Seq(
        Components.linkButton("Cancel") { _ => confirmVar.set(None) },
        // Proceed marker codes intent (§5.1): red for a destructive request
        // (Draw / Forfeit / discard-and-restart), green otherwise. Built inline
        // because both the label and the colour are reactive on the active
        // ConfirmRequest.
        button(
          typ := "button",
          className := "btn-link",
          cls("marker-red") <-- confirmVar.signal.map(_.exists(_.destructive)),
          cls("marker-green") <-- confirmVar.signal.map(_.exists(!_.destructive)),
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

  private def exportModal(): HtmlElement =
    modalShell(
      open = exportVar.signal.map(_.isDefined),
      onDismiss = () => exportVar.set(None),
      dialogClass = "export-dialog",
      head = Seq(
        h2(
          Components.newsprintClip(heading = true)(
            child.text <-- exportVar.signal.map {
              case Some(r) => s"Export (${r.format.toUpperCase})"
              case None    => "Export"
            }
          )
        )
      ),
      // The data rides a newspaper clipping; a big hand-drawn copy glyph sits
      // on its top-right corner (no separate Copy button).
      body = Seq(
        div(
          className := "export-clip",
          Components.newsprintClip(block = true)(
            child.text <-- exportVar.signal.map(_.map(_.content).getOrElse(""))
          ),
          button(
            typ := "button",
            className := "export-copy",
            aria.label := "Copy to clipboard",
            onClick --> { _ =>
              exportVar.now().foreach { r =>
                copyToClipboard(r.content)
                showToast(s"Copied ${r.format.toUpperCase} to clipboard")
              }
            },
            icon("copy")
          )
        )
      ),
      footer = Seq(
        Components.linkButton("Close") { _ => exportVar.set(None) }
      )
    )

  /** Auto-shown end-of-game card (§5.10): verdict headline, reason, a small
    * summary, and next-step actions. A modal-variant — reuses the paper frame
    * + photo-corner tape + blurred backdrop. Dismissible; the headline
    * (Layer 1) stays on the board after dismissal. */
  /** `spectator = true` (the Watch view) drops the "New Game" action — a
    * spectator can't start a new game for someone else's match — leaving just
    * Close. The board verdict + kings still show. */
  private def resultCard(spectator: Boolean = false): HtmlElement =
    modalShell(
      open = resultOpenSignal,
      onDismiss = () => resultDismissedVar.set(true),
      dialogClass = "result-dialog",
      backdropExtra = stickerRain(),
      // Head: the "Game Over" headline cutting — not the verdict itself
      // (§5.10). Who won + how drops into the body subtitle, where the winning
      // colour and end-reason are their own cuttings pasted into the sentence.
      head = Seq(
        h2(
          className := "result-headline",
          Components.newsprintClip(heading = true)("Game Over")
        )
      ),
      body = Seq(
        // Two king stickers — winner standing, loser toppled (a draw leaves
        // both up) — as a visual verdict above the sentence.
        div(
          className := "result-kings",
          children <-- stateVar.signal.map {
            case Some(s) => resultKings(s)
            case None    => List.empty
          }
        ),
        p(
          className := "result-subtitle",
          children <-- stateVar.signal.map {
            case Some(s) => resultSubtitle(s)
            case None    => List.empty
          }
        ),
        div(
          className := "result-summary",
          children <-- stateVar.signal.map {
            case Some(s) => resultSummary(s)
            case None    => List.empty
          }
        )
      ),
      // Close rides the default (yellow) marker, New Game the green one — and
      // New Game is dropped entirely when spectating.
      footer =
        Components.linkButton("Close") { _ => resultDismissedVar.set(true) } ::
          (if spectator then Nil
           else
             List(
               Components.linkButton("New Game", extraClass = "marker-green") {
                 _ =>
                   resultDismissedVar.set(true)
                   postNew()
               }
             ))
    )

  /** Two king stickers flanking the result card: the WINNER on the left
    * (standing), the LOSER on the right (toppled ~90°, the CSS pivots it about
    * its base). A draw has no winner, so it falls back to white-left /
    * black-right with both standing. */
  private def resultKings(s: BoardStateDto): List[HtmlElement] =
    def king(color: String, toppled: Boolean): HtmlElement =
      span(
        className :=
          s"result-king $color-piece${if toppled then " is-toppled" else ""}",
        pieceSvg("king")
      )
    s.status.winner match
      case Some(w) =>
        val loser = if w == "white" then "black" else "white"
        List(king(w, toppled = false), king(loser, toppled = true))
      case None =>
        List(king("white", toppled = false), king("black", toppled = false))

  /** The result subtitle as a handwritten sentence with two newspaper
    * cuttings pasted in — the winning colour and the end-reason — so it reads
    * "[Black] won by [checkmate!]". A draw has no winner, so its subject
    * cutting is "Draw" and the sentence becomes "[Draw] by [stalemate]". */
  private def resultSubtitle(s: BoardStateDto): List[HtmlElement] =
    verdictParts(
      s.status,
      clipClass = "result-clip",
      connClass = "result-conn",
      heading = false,
      verb = "won"
    )

  private def resultSummary(s: BoardStateDto): List[HtmlElement] =
    val fullMoves = (s.moveLog.length + 1) / 2
    val (whiteLost, blackLost) = Logic.capturedFromSquares(s.squares)
    val captures = whiteLost.size + blackLost.size
    List(
      resultStat(fullMoves, "moves"),
      resultStat(captures, "captures")
    )

  /** One mini stat as a newspaper cutting: the count is scrawled in
    * handwriting (pen-blue ink — "added in after the fact") next to the
    * typewriter label. */
  private def resultStat(count: Int, label: String): HtmlElement =
    Components.newsprintClip("result-stat")(
      span(className := "result-stat-num", count.toString),
      span(className := "result-stat-label", label)
    )

  private val stickerPieces =
    List("pawn", "knight", "bishop", "rook", "queen", "king")

  /** Layer 3 of the end-screen (§5.10): piece stickers raining behind the
    * result card. A decisive result rains the winner's colour; a draw rains
    * BOTH colours (same total count, split evenly + interleaved). Positions /
    * delays are index-derived (no RNG) so it's deterministic + cheap; count is
    * capped and the fall is a GPU-only transform animation (see
    * `.sticker-rain`). */
  private def stickerRain(): HtmlElement =
    val count = 18
    def rainPiece(color: String, i: Int): HtmlElement =
      span(
        className := s"sticker-rain-piece ${color}-piece",
        styleAttr := s"left: ${(i * 100) / count}%; " +
          s"animation-delay: ${(i % 6) * 0.4}s; " +
          s"animation-duration: ${3.4 + (i % 4) * 0.7}s;",
        pieceSvg(stickerPieces(i % stickerPieces.length))
      )
    div(
      className := "sticker-rain",
      children <-- stateVar.signal.map {
        case Some(s) =>
          s.status.winner match
            case Some(winner) =>
              (0 until count).toList.map(i => rainPiece(winner, i))
            case None if s.status.kind == "draw" =>
              (0 until count).toList
                .map(i => rainPiece(if i % 2 == 0 then "white" else "black", i))
            case None => List.empty
        case None => List.empty
      }
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
  private val postAnalyzeClient =
    SttpClientInterpreter().toClientThrowDecodeFailures(
      Endpoints.postAnalyze,
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
  private def bootstrapGame(
      load: Option[String],
  ): Unit =
    postCreateGameClient((sessionId, CreateGameRequest(load))).foreach {
      case Right(snapshot) =>
        gameIdVar.set(Some(snapshot.id))
        stateVar.set(Some(snapshot.state))
        currentAllowUndoVar.set(true) // local games always allow undo
        connectEvents(snapshot.id, spectator = false)
      case Left(err) =>
        showToast(err.error)
    }

  /** Tear down the live feed when leaving the board for a non-game
    * screen. Closing the EventSource drops our gateway-side spectator
    * membership immediately, so the live count stays honest instead of
    * lingering until the tab closes. */
  private def disconnectEvents(): Unit =
    sseHandle.foreach(_.close())
    sseHandle = None
    spectatorCountVar.set(0)

  private def connectEvents(gameId: String, spectator: Boolean): Unit =
    sseHandle.foreach(_.close())
    spectatorCountVar.set(0)
    // `role` lets the gateway tally spectators apart from players: the
    // Watch screen connects as a spectator (counted in the eye badge),
    // the Game screen as a player (sees the count but isn't in it).
    val role   = if spectator then "spectator" else "player"
    val source = new dom.EventSource(s"/api/games/$gameId/events?role=$role")
    sseHandle = Some(source)
    source.addEventListener(
      "state",
      (e: dom.MessageEvent) =>
        e.data.asInstanceOf[String].fromJson[BoardStateDto] match
          case Right(state) => stateVar.set(Some(state))
          case Left(err)    => showToast(s"Bad state payload: $err")
    )
    // Live spectator count for this game — the payload is a bare integer.
    source.addEventListener(
      "spectators",
      (e: dom.MessageEvent) =>
        e.data.asInstanceOf[String].trim.toIntOption
          .foreach(spectatorCountVar.set)
    )
    // The gateway refuses a spectator when the game disallows watching or
    // its spectator limit is full. Close the feed (so the EventSource
    // doesn't auto-reconnect into the same refusal), tell the user, and
    // bounce back to Start — they can't watch this one.
    source.addEventListener(
      "spectator-denied",
      (e: dom.MessageEvent) => {
        val reason = e.data.asInstanceOf[String]
        disconnectEvents()
        showToast(
          if reason == "full" then "This game's spectator slots are full."
          else "This game isn't open to spectators."
        )
        navigate(Screen.Start)
      }
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

  /** Pull the full position history of a finished game so the move log becomes a
    * clickable replay scrubber. Cached in `replayFramesVar`; `activePlyVar`
    * starts at the final position (no visible change until the user clicks a
    * move). Read-only endpoint, so it works for spectators too. Silent on
    * failure — replay just stays unavailable. */
  private def fetchReplay(id: String): Unit =
    fetchJson("GET", s"/api/games/$id/replay", None).onComplete {
      case scala.util.Success(raw) =>
        raw.fromJson[ReplayResponse] match
          case Right(r) =>
            val frames = r.frames.toVector
            replayFramesVar.set(frames)
            activePlyVar.set(math.max(0, frames.length - 1))
          case Left(_) => ()
      case scala.util.Failure(_) => ()
    }

  private def doExport(format: String): Unit =
    gameIdVar.now() match
      case Some(id) =>
        getStateClient((id, Some(format))).foreach {
          case Right(StateResponse.Export(resp)) => exportVar.set(Some(resp))
          case Right(_: StateResponse.View)      => ()
          case Left(err)                         => showToast(err.error)
        }
      case None => showToast("No active game")

  /** Fetch the finished game's PGN, then ask the engine to rate it. Result lands
    * in `analysisVar`, which the eval bar + move glyphs + detail panel render —
    * all keyed on `activePlyVar`, so analysis tracks the replay scrubber. On any
    * failure we clear `analyzeRequestedVar` so the CTA re-arms for a retry. */
  private def requestAnalysis(id: String): Unit =
    getStateClient((id, Some("pgn"))).foreach {
      case Right(StateResponse.Export(resp)) =>
        postAnalyzeClient(AnalyzeRequestDto(resp.content, AnalysisDepth)).foreach {
          case Right(analysis) => analysisVar.set(Some(analysis))
          case Left(_)         => analyzeRequestedVar.set(false)
        }
      case _ => analyzeRequestedVar.set(false)
    }
    // Safety net for a wedged backend that never answers: re-arm the button so
    // it can't sit on "Analyzing…" forever. The server enforces its own
    // (shorter) per-move budget, so a real run resolves via the branches above
    // well before this fires.
    dom.window.setTimeout(
      () =>
        if analyzeRequestedVar.now() && analysisVar.now().isEmpty then
          analyzeRequestedVar.set(false)
          showToast("Analysis timed out — try again"),
      AnalysisClientTimeoutMs
    )

  /** Engine search depth the UI asks for per move. Deliberately shallow: a
    * post-game pass runs a fixed-depth search on every ply (no time budget /
    * early-stop like the live bot), and quiescence makes each search expensive
    * on tactical, capture-heavy positions. Measured on a full game, depth 4 is
    * ~8× faster than depth 6 for essentially identical accuracy (the extra plies
    * barely move the eval), so deeper buys nothing but latency. */
  private val AnalysisDepth = 4
  /** Client-side stuck-button guard; longer than the server's analysis deadline
    * so it only fires if the backend never responds at all. */
  private val AnalysisClientTimeoutMs = 165000.0

  private def postAndToastErrors(
      f: Future[Either[ErrorDto, BoardStateDto]]
  ): Unit =
    f.foreach {
      case Right(_)  => ()
      case Left(err) => showToast(err.error)
    }

  /** Show a transient message. Cancels any in-flight auto-dismiss timer
    * first so a burst of toasts can't let an older countdown clear a
    * newer message; the toast is also click-to-dismiss (`toastElement`). */
  private def showToast(msg: String): Unit =
    toastTimer.foreach(dom.window.clearTimeout)
    toastVar.set(Some(msg))
    toastTimer = Some(dom.window.setTimeout(() => dismissToast(), 5000))

  private def dismissToast(): Unit =
    toastTimer.foreach(dom.window.clearTimeout)
    toastTimer = None
    toastVar.set(None)

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
          // Tournaments gets its own menu entry — a crown doodle + a
          // scribbled multi-stroke underline mark it as the headline feature.
          tournamentMenuItem(),
          Components.linkButton("Settings") { _ => navigate(Screen.Settings) }
        ),
        Components.sidePostIt(
          // Spectate any ongoing game (PvP / vs-bot / Lichess / tournament)
          // from one filterable list. Replaces the old single-purpose
          // "Watch a bot game" link.
          Components.linkButton("Spectate") { _ => navigate(Screen.Spectate) },
          Components.linkAnchor("Live analytics", "#analytics"),
          Components.linkAnchor("Help", "#help")
        )
      ),
      pieceShelf()
    )

  /** `#analytics` screen — analytics now live in Grafana (Spark domain metrics +
    * JVM/service + Kafka), so this is a launcher of links rather than an in-app
    * panel. Requires the stack up with `EXTRA=analytics,obs`. */
  private def analyticsScreen(): HtmlElement =
    Components.screenLayout("analytics")(
      Components.titleCard(
        Components.cornerPeach(),
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Analytics")
      ),
      Components.contentCard(
        div(
          className := "flex flex-col gap-2",
          p(
            "Game analytics, throughput, openings and Kafka/system metrics are " +
              "served by Grafana."
          ),
          a(
            href   := "http://localhost:3000/d/pichess-analytics",
            target := "_blank",
            "Game analytics dashboard ↗"
          ),
          a(
            href   := "http://localhost:3000/d/pichess-jvm-overview",
            target := "_blank",
            "JVM / service overview ↗"
          ),
          p(
            className := "opacity-60 text-sm",
            "Needs the stack running with EXTRA=analytics,obs (Grafana on :3000)."
          )
        )
      )
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
        Components.cornerPeach(),
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
        Components.cornerPeach(),
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Help")
      ),
      // No outer content-card: each `.help-section` is its own paper
      // panel, so a wrapper card would just nest cards inside a card.
      HelpView.render()
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
        Components.cornerPeach(),
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("New Game")
      ),
      Components.contentCard(
        Components.tabStrip[NewGameMode](
          newGameModeVar,
          Seq(
            (NewGameMode.Local, "Local",  true),
            (NewGameMode.Host,  "Host",   true),
            (NewGameMode.Bot,   "Vs Bot", true)
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
      importField(),
      Components.linkButton("Start local game") { _ => createLocalGame() }
    )

  /** Optional "start from a position" paste box, offered on the local + vs-bot
    * modes only (a hosted multiplayer game always starts from the standard
    * position). Same hand-drawn grid-paper field as the board's Load modal;
    * blank means a fresh game. Feeds `newGameImportVar` → `CreateGameRequest.load`. */
  private def importField(): HtmlElement =
    div(
      className := "mode-import flex flex-col gap-2",
      span(className := "mode-import-label", "Start from a position (optional)"),
      div(
        className := "load-field",
        textArea(
          className := "load-input",
          rows := 4,
          placeholder := "Paste FEN, PGN, or JSON — or leave blank for a new board.",
          spellCheck := false,
          controlled(
            value <-- newGameImportVar.signal,
            onInput.mapToValue --> newGameImportVar
          )
        )
      )
    )

  private def botModeDetails(): HtmlElement =
    div(
      className := "mode-details flex flex-col gap-4 items-stretch",
      p(
        className := "mode-blurb",
        "Play the built-in engine — a hand-crafted + NNUE hybrid eval " +
          "with alpha-beta search. Pick your colour and how hard it plays."
      ),
      div(
        className := "host-form flex flex-col gap-3",
        Components.formRow("I play as")(
          Components.selectInput(
            vsBotPlayerSideVar,
            Seq("white" -> "White", "black" -> "Black")
          )
        ),
        // Values match `chess.bot.engine.Difficulty` enum names; the server
        // parses them case-insensitively and maps each to a think-time budget
        // (harder = thinks longer + deeper). The labels surface that so players
        // know "Max" really will sit and calculate.
        Components.formRow("Difficulty")(
          Components.selectInput(
            vsBotDifficultyVar,
            Seq(
              "Beginner" -> "Beginner (instant, blunders)",
              "Easy"     -> "Easy (instant)",
              "Medium"   -> "Medium (~0.4s)",
              "Hard"     -> "Hard (~1s)",
              "Expert"   -> "Expert (~2s)",
              "Max"      -> "Max (full strength, ~6s)"
            )
          )
        ),
        Components.checkboxRow(vsBotAllowUndoVar, "Allow undo")
      ),
      importField(),
      Components.linkButton("Start game vs bot") { _ => createBotGame() }
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
        Components.checkboxRow(hostAllowUndoVar, "Allow undo"),
        Components.checkboxRow(hostAllowSpectateVar, "Allow spectators"),
        div(
          // Spectator limit is moot when spectators aren't allowed — fade it
          // out (§5.9 erased disabled state) and block interaction.
          className <-- hostAllowSpectateVar.signal
            .map(on => if on then "" else "is-erased"),
          Components.formRow("Spectator limit")(
            Components.rangeSlider(hostSpectatorLimitVar, min = 0, max = 64)
          )
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
    postCreateGameClient(
      (sessionId, CreateGameRequest(newGameImport(), None))
    ).foreach {
      case Right(snapshot) =>
        gameIdVar.set(Some(snapshot.id))
        stateVar.set(Some(snapshot.state))
        // Local games always allow undo.
        currentAllowUndoVar.set(true)
        currentGameIsLocalVar.set(true)
        pendingTitleVar.set(Some(Logic.GameTitle.local))
        newGameImportVar.set("")
        navigate(Screen.Game(snapshot.id))
      case Left(err) => showToast(err.error)
    }

  /** The optional import text from the New Game screen, trimmed to `None` when
    * blank so a fresh game starts from the standard position. */
  private def newGameImport(): Option[String] =
    val raw = newGameImportVar.now().trim
    if raw.isEmpty then None else Some(raw)

  /** Mint a vs-bot game from the new-game menu. The player picks their
    * own colour; the bot takes the opposite. If the bot is white the
    * gateway plays its opening before replying, so the returned snapshot
    * already carries that move. Navigation to the game screen wires SSE
    * (via `enterGame`) for the bot's subsequent replies. */
  private def createBotGame(): Unit =
    val playerWhite = vsBotPlayerSideVar.now() == "white"
    val settings = VsBotSettings(
      botSide    = if playerWhite then "black" else "white",
      difficulty = vsBotDifficultyVar.now(),
      allowUndo  = vsBotAllowUndoVar.now(),
    )
    postCreateGameClient(
      (
        sessionId,
        CreateGameRequest(newGameImport(), Some(settings))
      )
    ).foreach {
      case Right(snapshot) =>
        gameIdVar.set(Some(snapshot.id))
        stateVar.set(Some(snapshot.state))
        currentAllowUndoVar.set(settings.allowUndo)
        currentGameIsLocalVar.set(true)
        // "<nickname> vs Bot (<difficulty>)", placed by the side the player
        // chose (the bot took the opposite colour via `settings.botSide`).
        pendingTitleVar.set(
          Some(
            Logic.GameTitle.vsBot(
              nicknameVar.now(),
              vsBotPlayerSideVar.now(),
              settings.difficulty
            )
          )
        )
        newGameImportVar.set("")
        navigate(Screen.Game(snapshot.id))
      case Left(err) => showToast(err.error)
    }

  // -- Join screen ----------------------------------------------------------

  // ==========================================================================
  // Spectate + Tournaments (Phase 5)
  //
  // Two screens sharing the FilterBar / scrap-table / Grafana-refresh
  // vocabulary (design.md §5.3/§5.8): a unified list of ongoing games to
  // WATCH, and the list of NowChess tournaments to ENTER piChess into. Data is
  // same-origin from the gateway — `GET /spectate/games`
  // (chess.controller.SpectateIndex) and `GET /tournament/list`
  // (chess.controller.TournamentProxy). Pure transforms live in `Logic`.
  // ==========================================================================

  /** The start-screen Tournament entry: a crown doodle + a scribbled
    * multi-stroke underline, on the same `.btn-link` base as its siblings. */
  private def tournamentMenuItem(): HtmlElement =
    button(
      typ := "button",
      className := "btn-link inline-flex items-center gap-1",
      onClick --> { _ => navigate(Screen.Tournaments) },
      icon("crown"),
      span(className := "scribble-underline", "Tournament")
    )

  // -- Spectate ---------------------------------------------------------------

  private val spectateGamesVar: Var[List[OngoingGame]]     = Var(Nil)
  private val spectateFilterVar: Var[Logic.SpectateFilter] =
    Var(Logic.SpectateFilter.All)
  private val spectateIntervalVar: Var[Option[Int]]        = Var(None)
  // Default-on: show only piChess's own tournament games (cheap + local — the
  // gateway asks our bot service, no external call). Unchecking fetches every
  // tournament's games (`scope=all`, the slower external fan).
  private val spectateBotOnlyVar: Var[Boolean]             = Var(true)

  private def spectateScreen(): HtmlElement =
    Components.screenLayout("spectate")(
      Components.titleCard(
        Components.cornerPeach(),
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Spectate")
      ),
      Components.contentCard(
        onMountCallback { _ => refreshSpectateGames() },
        // FilterBar (reactive on the list for live counts), a "challenge a
        // fresh Lichess bot-game to watch" link, and the refresh bar.
        // Two stacked rows so the panel doesn't stretch wide: the scope toggle
        // + challenge + refresh controls on top, the filter chips alone below.
        div(
          className := "flex flex-col gap-2 mb-3",
          // Flipping the scope re-fetches (ours ⇄ all). `.changes` only — the
          // first load is the onMountCallback above, so we don't double-fetch.
          spectateBotOnlyVar.signal.changes --> { (_: Boolean) =>
            refreshSpectateGames()
          },
          // Top: the scope toggle (left) and the challenge link + refresh /
          // auto-poll controls (right).
          div(
            className := "flex flex-row items-center justify-between gap-3 flex-wrap",
            Components.checkboxRow(spectateBotOnlyVar, "piChess bot games only"),
            div(
              className := "flex flex-row items-center gap-3 flex-wrap",
              // Lichess is an opt-in external integration — hide the challenge
              // link unless the server has it configured (pichess-lichess meta).
              if lichessEnabled then
                Components.linkButton("Challenge a bot") { _ => startLichessWatch() }
              else emptyNode,
              refreshBar(spectateIntervalVar, () => refreshSpectateGames())
            )
          ),
          // Bottom: the type-filter chips (both shape the list).
          child <-- spectateGamesVar.signal.map { games =>
            Components.tabStrip(spectateFilterVar, Logic.spectateFilterChips(games))
          }
        ),
        // The table, reactive on both the games and the active filter.
        // Height-capped + scrolled so a long games list can't tilt the card
        // off-screen (a full tournament can return hundreds of games).
        ledgerScroll(
          child <-- spectateGamesVar.signal
            .combineWith(spectateFilterVar.signal)
            .map { case (games, f) =>
              spectateTable(Logic.filterGames(games, f))
            }
        ),
        // Grafana-style auto-poll: tick only while an interval is chosen
        // (None ⇒ empty ⇒ nothing polls). Bound to the element, so it stops
        // when the screen unmounts.
        spectateIntervalVar.signal.flatMapSwitch {
          case Some(n) => EventStream.periodic(n * 1000)
          case None    => EventStream.empty
        } --> { (_: Int) => refreshSpectateGames() }
      )
    )

  private def refreshSpectateGames(): Unit =
    // `ours` (default) keeps it cheap + local; `all` opts into the full
    // external tournament fan — see SpectateIndex.
    val scope = if spectateBotOnlyVar.now() then "ours" else "all"
    fetchJson("GET", s"/spectate/games?scope=$scope", None).onComplete {
      case scala.util.Success(raw) =>
        raw.fromJson[List[OngoingGame]] match
          case Right(games) => spectateGamesVar.set(games)
          case Left(_)      => showToast("Couldn't read the games list.")
      case scala.util.Failure(err) =>
        showToast(s"Could not fetch games: ${err.getMessage}")
    }

  private def spectateTable(games: List[OngoingGame]): HtmlElement =
    Components.scrapTable(
      Seq(
        "White"    -> "",
        "Black"    -> "",
        "Status"   -> "col-status",
        "Watching" -> "col-num",
        ""         -> "col-action"
      ),
      games.map(spectateRow),
      "No games to watch right now."
    )

  private def spectateRow(g: OngoingGame): HtmlElement =
    val (badgeLabel, badgeVariant) = Logic.gameBadge(g)
    val watching =
      if g.limit > 0 then s"${g.spectators}/${g.limit}"
      else g.spectators.toString
    tr(
      td(g.white),
      td(g.black),
      td(
        className := "col-status",
        Components.statusBadge(badgeLabel, badgeVariant)
      ),
      td(className := "col-num", watching),
      td(className := "col-action", spectateAction(g))
    )

  /** The Spectate action for a row — an in-flow `.btn-link` (matching the
    * `publicLobbyAction` list-row precedent; a per-row post-it would flood the
    * secondary colour, §2.4). Native games (pvp/pvbot) are watched directly via
    * the existing SSE board; external games (tournament/lichess) open a
    * server-side mirror first. A full game is listed but its link is erased
    * (§5.9 — loose handwriting), not removed. */
  private def spectateAction(g: OngoingGame): HtmlElement =
    if !g.spectateable then
      button(
        typ := "button",
        className := "btn-link is-erased",
        aria.disabled := true,
        "Spectate"
      )
    else
      // The aggregator already labels each row's sides (real names for
      // tournament / Lichess mirrors, "Player" / "piChess (bot)" / colour
      // words for native games), so the watch title comes straight off the row.
      val title = Logic.GameTitle.players(g.white, g.black)
      g.gameType match
        case "pvp" | "pvbot" =>
          Components.linkButton("Spectate") { _ =>
            pendingTitleVar.set(Some(title))
            navigate(Screen.Watch(g.id))
          }
        case "tournament" =>
          Components.linkButton("Spectate") { _ =>
            openMirror(
              s"/tournament/${g.tournamentId.getOrElse("")}/game/${g.id}/spectate",
              title
            )
          }
        case "lichess" =>
          Components.linkButton("Spectate") { _ =>
            openMirror(s"/lichess/games/${g.id}/spectate", title)
          }
        case _ => span()

  /** POST a spectate-mirror endpoint, then open the returned mirror game in
    * the read-only Watch view. Same response parse as `startLichessWatch`.
    * `title` is staged for the board so the mirror shows the two players. */
  private def openMirror(path: String, title: Logic.GameTitle): Unit =
    showToast("Opening spectate…")
    fetchJson("POST", path, None).onComplete {
      case scala.util.Success(raw) =>
        try
          val obj      = js.JSON.parse(raw).asInstanceOf[js.Dynamic]
          val mirrorId = obj.mirrorId.asInstanceOf[String]
          dismissToast()
          pendingTitleVar.set(Some(title))
          navigate(Screen.Watch(mirrorId))
        catch
          case _: Throwable =>
            showToast("Couldn't open spectate (bad response).")
      case scala.util.Failure(err) =>
        showToast(s"Couldn't open spectate: ${err.getMessage}")
    }

  // -- Tournaments ------------------------------------------------------------

  private val tournamentsVar: Var[List[Logic.TournamentRow]] = Var(Nil)
  private val tournamentsIntervalVar: Var[Option[Int]]       = Var(None)

  private def tournamentsScreen(): HtmlElement =
    Components.screenLayout("tournaments")(
      Components.titleCard(
        Components.cornerPeach(),
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Tournaments")
      ),
      Components.contentCard(
        onMountCallback { _ => refreshTournaments() },
        div(
          className := "flex flex-row items-center justify-end gap-3 flex-wrap mb-3",
          refreshBar(tournamentsIntervalVar, () => refreshTournaments())
        ),
        ledgerScroll(child <-- tournamentsVar.signal.map(tournamentTable)),
        tournamentsIntervalVar.signal.flatMapSwitch {
          case Some(n) => EventStream.periodic(n * 1000)
          case None    => EventStream.empty
        } --> { (_: Int) => refreshTournaments() }
      )
    )

  private def refreshTournaments(): Unit =
    fetchJson("GET", "/tournament/list", None).onComplete {
      case scala.util.Success(raw) =>
        raw.fromJson[Logic.TournamentList] match
          case Right(list) => tournamentsVar.set(Logic.orderTournaments(list))
          case Left(_)     => showToast("Couldn't read the tournament list.")
      case scala.util.Failure(err) =>
        showToast(s"Could not fetch tournaments: ${err.getMessage}")
    }

  private def tournamentTable(rows: List[Logic.TournamentRow]): HtmlElement =
    Components.scrapTable(
      Seq(
        "Tournament" -> "",
        "Players"    -> "col-num",
        "Round"      -> "col-num",
        "Status"     -> "col-status",
        ""           -> "col-action"
      ),
      rows.map(tournamentRow),
      "No tournaments right now."
    )

  private def tournamentRow(t: Logic.TournamentRow): HtmlElement =
    val (badgeLabel, badgeVariant) = Logic.tournamentBadge(t.status)
    tr(
      td(t.fullName),
      td(className := "col-num", t.nbPlayers.toString),
      td(className := "col-num", t.round.toString),
      td(
        className := "col-status",
        Components.statusBadge(badgeLabel, badgeVariant)
      ),
      td(className := "col-action", tournamentAction(t))
    )

  private def tournamentAction(t: Logic.TournamentRow): HtmlElement =
    if Logic.canEnterTournament(t.status) then
      Components.linkButton("Enter piChess") { _ =>
        enterTournament(t.id, t.fullName)
      }
    else span()

  private def enterTournament(id: String, name: String): Unit =
    showToast(s"Entering $name…")
    fetchJson("POST", s"/tournament/$id/join", None).onComplete {
      case scala.util.Success(_) =>
        showToast(s"piChess is entering $name")
        refreshTournaments()
      case scala.util.Failure(err) =>
        showToast(s"Couldn't enter: ${err.getMessage}")
    }

  // -- Shared Grafana-style refresh bar ---------------------------------------

  /** Manual ⟳ + an auto-poll interval select. The interval `Var` is the single
    * source of truth that drives both the select and the screen's poll stream;
    * `None` ("Off") is the default so nothing polls until the user opts in. */
  /** Icon button with the doodle refresh glyph (the old "⟳" text glyph read as
    * a stray character). Used by the refresh bar + the Join screen. */
  private def refreshButton(onRefresh: () => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-icon",
      aria.label := "Refresh",
      onClick --> { _ => onRefresh() },
      icon("refresh")
    )

  private def refreshBar(
      intervalVar: Var[Option[Int]],
      onRefresh: () => Unit
  ): HtmlElement =
    div(
      className := "flex flex-row items-center gap-2 flex-shrink-0",
      refreshButton(() => onRefresh()),
      span(className := "font-hand text-text-secondary", "Auto"),
      Components.selectInput[Option[Int]](
        intervalVar,
        Logic.refreshIntervals,
        i => i.fold("off")(_.toString)
      )
    )

  private val joinCodeVar: Var[String] = Var("")

  private def joinScreen(): HtmlElement =
    Components.screenLayout("join")(
      Components.titleCard(
        Components.cornerPeach(),
        Components.backLink(() => navigate(Screen.Start)),
        Components.screenHeading("Join Game")
      ),
      Components.contentCard(
        onMountCallback { _ => refreshPublicLobbies() },
        Components.formRow("Invite code")(
          Components.textInput(joinCodeVar, placeholder := "ABCDEF")
        ),
        Components.linkButton("Join", extraClass = "marker-green") { _ =>
          joinByCode(joinCodeVar.now())
        },
        div(
          className := "join-public flex flex-col gap-2",
          div(
            className := "flex flex-row items-center justify-between",
            h2(
              className := "section-heading",
              Components.newsprintClip()("Public lobbies")
            ),
            refreshButton(() => refreshPublicLobbies())
          ),
          // Canonical ruled-ledger table (the spectate screen's `scrapTable`),
          // not a bespoke list. Capped + scrolled like the others.
          ledgerScroll(child <-- publicLobbiesVar.signal.map(joinTable))
        )
      )
    )

  private def joinTable(lobbies: List[LobbyJson]): HtmlElement =
    Components.scrapTable(
      Seq(
        "Host"   -> "",
        "Code"   -> "col-num",
        "Status" -> "col-status",
        ""       -> "col-action"
      ),
      lobbies.map(publicLobbyRow),
      "No public games right now."
    )

  private def refreshPublicLobbies(): Unit =
    fetchJson("GET", s"$lobbyBaseUrl/lobbies/public", None).onComplete {
      case scala.util.Success(raw) =>
        publicLobbiesVar.set(parseLobbyList(raw))
      case scala.util.Failure(err) =>
        showToast(s"Could not fetch lobbies: ${err.getMessage}")
    }

  /** One row in the public-game browser: host + a state badge, plus the
    * action that fits the lobby's state — an open seat offers "Play", a
    * running spectatable game offers "Spectate". */
  private def publicLobbyRow(l: LobbyJson): HtmlElement =
    val (badgeLabel, badgeVariant) = l.status match
      case "Waiting" => ("Open", "waiting")
      case "Full"    => ("Full", "full")
      case "Started" => ("Live", "live")
      case other     => (other, "")
    tr(
      td(l.hostNickname),
      td(className := "col-num", l.inviteCode),
      td(
        className := "col-status",
        Components.statusBadge(badgeLabel, badgeVariant)
      ),
      td(className := "col-action", publicLobbyAction(l))
    )

  /** The state-appropriate action for a browser row, or nothing when
    * there's no action (a full lobby yet to start, or a running game
    * that disallows spectators). */
  private def publicLobbyAction(l: LobbyJson): HtmlElement =
    l.status match
      case "Waiting" =>
        Components.linkButton("Play") { _ => joinByCode(l.inviteCode) }
      case "Started" =>
        l.gameId.toOption match
          case Some(gid) if l.allowSpectate =>
            Components.linkButton("Spectate") { _ =>
              navigate(Screen.Watch(gid))
            }
          case _ => span()
      case _ => span()

  /** Enter a lobby by invite code. We look the lobby up first (a
    * read-only GET) and branch on its state instead of blindly POSTing a
    * player-join: a `Waiting` lobby takes us as the second player
    * (first-come-first-served), a `Started` game drops us into the
    * read-only spectator view, and a `Full` lobby parks us on the lobby
    * screen to await the start. This is what lets a spectator join a
    * game that's already running. */
  private def joinByCode(rawCode: String): Unit =
    val code = rawCode.trim.toUpperCase
    if code.isEmpty then showToast("Enter an invite code first")
    else
      fetchJson("GET", s"$lobbyBaseUrl/lobbies/by-code/$code", None).onComplete {
        case scala.util.Success(raw) =>
          parseLobbyJson(raw) match
            case Some(l) => routeIntoLobby(l)
            case None    => showToast("Couldn't read that lobby")
        case scala.util.Failure(_) =>
          showToast(s"No lobby with code $code")
      }

  /** Decide where an invite code takes us, from the lobby's state and
    * whether our own session is already one of its players. */
  private def routeIntoLobby(l: LobbyJson): Unit =
    currentLobbyVar.set(Some(l))
    val isPlayer = l.hostSessionId == sessionId ||
      l.guestSessionId.toOption.contains(sessionId)
    l.status match
      case "Waiting" =>
        joinLobbyAsPlayer(l.inviteCode) // open seat — claim it
      case "Full" =>
        if isPlayer || l.allowSpectate then
          // We're a player, or we'll wait here as a spectator — the
          // poller routes us to the board once the host starts it.
          navigate(Screen.Lobby(l.inviteCode))
        else showToast("This game is full and not open to spectators")
      case "Started" =>
        l.gameId.toOption match
          case Some(gid) if isPlayer        =>
            currentGameIsLocalVar.set(false) // multiplayer lobby game
            navigate(Screen.Game(gid))
          case Some(gid) if l.allowSpectate => navigate(Screen.Watch(gid))
          case Some(_)                      =>
            showToast("This game isn't open to spectators")
          case None                         => showToast("Game is starting…")
      case _ => showToast("This lobby is closed")

  /** POST the player-join (claim the open seat). On a lost race — someone
    * took the seat between our look-up and our join — re-resolve the
    * lobby so we still land somewhere sensible (spectating) rather than
    * at a dead-end error. */
  private def joinLobbyAsPlayer(inviteCode: String): Unit =
    val payload = js.JSON.stringify(
      js.Dynamic.literal(
        guestNickname = nicknameVar.now(),
        guestSessionId = sessionId
      )
    )
    fetchJson(
      "POST",
      s"$lobbyBaseUrl/lobbies/by-code/$inviteCode/join",
      Some(payload)
    ).onComplete {
      case scala.util.Success(raw) =>
        parseLobbyJson(raw) match
          case Some(l) =>
            currentLobbyVar.set(Some(l))
            navigate(Screen.Lobby(l.inviteCode))
          case None => showToast("Bad lobby payload")
      case scala.util.Failure(_) =>
        fetchJson(
          "GET",
          s"$lobbyBaseUrl/lobbies/by-code/$inviteCode",
          None
        ).onComplete {
          case scala.util.Success(raw) =>
            parseLobbyJson(raw) match
              case Some(l) if l.status != "Waiting" => routeIntoLobby(l)
              case _ => showToast("Couldn't join — the lobby may be full")
          case scala.util.Failure(_) => showToast("Couldn't join the lobby")
        }
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
        Components.cornerPeach(),
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
      // Once the host has started the game: players go to the interactive
      // board; everyone else (spectators) gets the read-only watch view.
      if l.status == "Started" && l.gameId.isDefined then
        val gid      = l.gameId.get
        val isPlayer = l.hostSessionId == sessionId ||
          l.guestSessionId.toOption.contains(sessionId)
        if isPlayer then
          Components.linkAnchor("Game started — go to board", s"#game/$gid")
        else
          Components.linkAnchor("Game started — spectate", s"#watch/$gid")
      else span()
    )

  private def refreshLobbyByCode(code: String): Unit =
    fetchJson("GET", s"$lobbyBaseUrl/lobbies/by-code/$code", None).onComplete {
      case scala.util.Success(raw) =>
        parseLobbyJson(raw).foreach { l =>
          currentLobbyVar.set(Some(l))
          // Once the host starts the game, auto-route off the lobby
          // screen: players to the interactive board, everyone else to
          // the read-only spectator view (when spectating is allowed).
          if l.status == "Started" then
            l.gameId.toOption.foreach { gid =>
              val isPlayer = l.hostSessionId == sessionId ||
                l.guestSessionId.toOption.contains(sessionId)
              if isPlayer then
                currentGameIsLocalVar.set(false) // multiplayer lobby game
                navigate(Screen.Game(gid))
              else if l.allowSpectate then navigate(Screen.Watch(gid))
            }
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
            currentGameIsLocalVar.set(false) // multiplayer lobby game
            navigate(Screen.Game(snapshot.id))
          case scala.util.Failure(err) =>
            showToast(s"Start failed: ${err.getMessage}")
        }
      case Left(err) => showToast(err.error)
    }

  // -- Shared helpers -------------------------------------------------------


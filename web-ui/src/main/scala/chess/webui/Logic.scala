package chess.webui

import zio.json.*

import chess.api.{
  BoardStateDto,
  ClockDto,
  GameAnalysisDto,
  MoveAnalysisDto,
  MoveEntryDto,
  OngoingGame,
  OpeningDto,
  SquareDto
}

/** Pure helpers used by the Laminar `Main` component.
  *
  * These have no DOM or Laminar dependency so they can be unit-tested in plain
  * zio-test on Scala.js — the `Main.scala` file itself is harder to exercise
  * because it wires together Laminar signals and DOM events.
  */
object Logic:

  /** Active page theme. The Scala.js bundle reads / writes this; the
    * synchronous bootstrap script in `HtmlPage.scala` applies the matching
    * `dark` class on `<html>` before the bundle loads to avoid FOUT.
    */
  enum Theme:
    case Light, Dark

  /** Decide which theme to start in.
    *
    * Light is the default — only an explicit stored `dark` opts in. The OS
    * `prefers-color-scheme` is intentionally ignored so first-time visitors
    * always see the same palette regardless of system settings; the user can
    * switch via the toggle and the choice persists.
    *
    * The `prefersDark` parameter is retained for compatibility with the call
    * site and historical tests, but it no longer affects the result.
    */
  def decideInitialTheme(
      stored: Option[String],
      prefersDark: Boolean = false
  ): Theme =
    val _ = prefersDark
    stored match
      case Some("dark") => Theme.Dark
      case _            => Theme.Light

  /** `true` when moving the piece at `from` to `to` constitutes a pawn
    * promotion — i.e. the piece is a pawn and the destination rank is the
    * opponent's back rank.
    */
  def isPawnPromotion(
      from: String,
      to: String,
      state: BoardStateDto
  ): Boolean =
    state.squares.find(_.pos == from).flatMap(_.piece) match
      case Some("pawn") =>
        val row = to.charAt(1)
        row == '8' || row == '1'
      case _ => false

  /** Group a chronological move log into `(moveNumber, white, blackOpt)`
    * triples — one row per White-Black pair; a dangling white half-move appears
    * alone at the end with `None` in the black slot.
    */
  def groupMovesByTwo(
      moves: List[MoveEntryDto]
  ): List[(Int, MoveEntryDto, Option[MoveEntryDto])] =
    moves
      .grouped(2)
      .zipWithIndex
      .map { case (pair, idx) => (idx + 1, pair.head, pair.lift(1)) }
      .toList

  /** Replay styling for the half-move at flat index `i`, given the shown frame
    * (`activePly`: 0 = initial position … N = final). Returns `(isActive,
    * isFuture)`: the move that produced the shown frame (`activePly - 1`) is
    * active (emphatic underline); moves not yet played at this frame (`i >=
    * activePly`) are muted. Pure — unit-tested in `LogicSpec`. */
  def replayMoveState(i: Int, activePly: Int): (Boolean, Boolean) =
    (i == activePly - 1, i >= activePly)

  /** (SAN-key, piece-type-name) pairs offered in the promotion dialog — four
    * entries, the same four for both colors. The piece-type name is used as the
    * symbol id when rendering `<use href="/web/pieces/<name>.svg#<name>"/>`.
    */
  val promotionChoices: List[(String, String)] = List(
    "Q" -> "queen",
    "R" -> "rook",
    "B" -> "bishop",
    "N" -> "knight"
  )

  /** Map a wire-format draw reason to a user-readable phrase. The kebab-case
    * tokens come from `DrawReason.name` on the JVM side. Unknown tokens fall
    * through unchanged so a future reason still renders something sensible.
    */
  def humanizeDrawReason(reason: String): String =
    reason match
      case "fiftyMoveRule"        => "50-move rule"
      case "threefoldRepetition"  => "threefold repetition"
      case "fivefoldRepetition"   => "fivefold repetition"
      case "stalemate"            => "stalemate"
      case "insufficientMaterial" => "insufficient material"
      case other                  => other

  // The starting position has these piece-type counts per side. We diff the
  // current squares against this multiset to derive captured material — no
  // server-side support needed.
  private val startingPieceTypes: List[String] =
    List.fill(8)("pawn") ++ List.fill(2)("rook") ++ List.fill(2)("knight") ++
      List.fill(2)("bishop") ++ List("queen", "king")

  // Multiset diff: the elements of `expected` that aren't matched by an
  // element in `actual`, counting duplicates correctly.
  private def multisetDiff[A](expected: List[A], actual: List[A]): List[A] =
    actual
      .foldLeft(expected) { (remaining, item) =>
        val idx = remaining.indexOf(item)
        if idx < 0 then remaining else remaining.patch(idx, Nil, 1)
      }

  /** Captured pieces, per color, derived from the current board.
    *
    * Returns `(whiteCaptured, blackCaptured)` where `whiteCaptured` is the
    * white pieces that have been *taken* (i.e. captured by black), in
    * descending order of value so the tray reads naturally.
    */
  def capturedFromSquares(
      squares: List[SquareDto]
  ): (List[String], List[String]) =
    val whiteAlive = squares.collect {
      case s if s.pieceColor.contains("white") && s.piece.nonEmpty =>
        s.piece.get
    }
    val blackAlive = squares.collect {
      case s if s.pieceColor.contains("black") && s.piece.nonEmpty =>
        s.piece.get
    }
    val whiteLost = sortByValue(multisetDiff(startingPieceTypes, whiteAlive))
    val blackLost = sortByValue(multisetDiff(startingPieceTypes, blackAlive))
    (whiteLost, blackLost)

  // Highest-value piece first so multiple captures cluster as K-Q-R-B-N-P.
  // The king gets a sentinel value so the (illegal but possible) edge case
  // of a missing king sorts to the front rather than crashing on lookup.
  private val pieceValues: Map[String, Int] = Map(
    "king" -> 100,
    "queen" -> 9,
    "rook" -> 5,
    "bishop" -> 3,
    "knight" -> 3,
    "pawn" -> 1
  )

  private def sortByValue(types: List[String]): List[String] =
    types.sortBy(t => -pieceValues(t))

  // --------------------------------------------------------------------------
  // Spectate + Tournament UI (Phase 5) — pure helpers for the unified
  // ongoing-games list and the tournament list. The DOM/Laminar wiring stays
  // in Main.scala; everything testable lives here.
  // --------------------------------------------------------------------------

  /** The type filter for the unified Spectate list. The `gameType` tokens come
    * from the gateway aggregator (`chess.controller.SpectateIndex`).
    */
  enum SpectateFilter:
    case All, Pvp, Pvbot, Lichess, Tournament

  /** Chip label for a filter. */
  def spectateFilterLabel(f: SpectateFilter): String = f match
    case SpectateFilter.All        => "All"
    case SpectateFilter.Pvp        => "PvP"
    case SpectateFilter.Pvbot      => "PvBot"
    case SpectateFilter.Lichess    => "Bot v Lichess"
    case SpectateFilter.Tournament => "Tournament"

  /** Does a game of `gameType` belong under filter `f`? `All` matches every
    * row; each type chip matches its own aggregator token.
    */
  def matchesFilter(gameType: String, f: SpectateFilter): Boolean = f match
    case SpectateFilter.All        => true
    case SpectateFilter.Pvp        => gameType == "pvp"
    case SpectateFilter.Pvbot      => gameType == "pvbot"
    case SpectateFilter.Lichess    => gameType == "lichess"
    case SpectateFilter.Tournament => gameType == "tournament"

  /** The games matching `f`, order preserved. */
  def filterGames(games: List[OngoingGame], f: SpectateFilter): List[OngoingGame] =
    games.filter(g => matchesFilter(g.gameType, f))

  // The chips, in display order. All first, then the four types.
  private val spectateFilterOrder: List[SpectateFilter] = List(
    SpectateFilter.All,
    SpectateFilter.Pvp,
    SpectateFilter.Pvbot,
    SpectateFilter.Lichess,
    SpectateFilter.Tournament
  )

  /** The FilterBar chips as `(filter, "Label (n)", enabled)` triples for
    * [[Components.tabStrip]]: each carries its live count; a type chip with no
    * games is disabled (the §5.9 erased treatment), while `All` stays enabled
    * so there's always a way back to the full list.
    */
  def spectateFilterChips(
      games: List[OngoingGame]
  ): List[(SpectateFilter, String, Boolean)] =
    spectateFilterOrder.map { f =>
      val n = filterGames(games, f).size
      val enabled = f == SpectateFilter.All || n > 0
      (f, s"${spectateFilterLabel(f)} ($n)", enabled)
    }

  /** Auto-refresh interval options for the Grafana-style bar — `None` is "Off"
    * and is the default (nothing polls unless the user opts in).
    */
  val refreshIntervals: List[(Option[Int], String)] = List(
    None     -> "Off",
    Some(5)  -> "5s",
    Some(10) -> "10s",
    Some(30) -> "30s",
    Some(60) -> "1m"
  )

  /** Status badge `(label, variant)` for one ongoing-game row. A full game is
    * still listed, just flagged — the row's action is disabled separately.
    */
  def gameBadge(g: OngoingGame): (String, String) =
    if !g.spectateable then ("Full", "full") else ("Live", "live")

  /** Status badge `(label, variant)` for a tournament row. */
  def tournamentBadge(status: String): (String, String) = status match
    case "created"  => ("Open", "waiting")
    case "started"  => ("Live", "live")
    case "finished" => ("Done", "done")
    case other      => (other, "")

  /** piChess can only enter a tournament while it's still `created`. */
  def canEnterTournament(status: String): Boolean = status == "created"

  /** One tournament row from the NowChess `GET /api/tournament` envelope.
    * Extra fields (clock, variant, standing, …) are ignored by the decoder.
    */
  final case class TournamentRow(
      id: String,
      fullName: String,
      nbPlayers: Int,
      status: String,
      round: Int
  )
  object TournamentRow:
    given JsonDecoder[TournamentRow] = DeriveJsonDecoder.gen[TournamentRow]

  /** The `GET /api/tournament` envelope. */
  final case class TournamentList(
      created: List[TournamentRow],
      started: List[TournamentRow],
      finished: List[TournamentRow]
  )
  object TournamentList:
    given JsonDecoder[TournamentList] = DeriveJsonDecoder.gen[TournamentList]

  /** Flatten the envelope into one display list, most-actionable first:
    * joinable (`created`) → live (`started`) → done (`finished`).
    */
  def orderTournaments(list: TournamentList): List[TournamentRow] =
    list.created ++ list.started ++ list.finished

  // --------------------------------------------------------------------------
  // Clock display (Phase D). The game-service is the source of truth; these
  // pure helpers only format + interpolate the running side between pushes.
  // --------------------------------------------------------------------------

  /** Format remaining milliseconds as a chess clock: `m:ss` normally, with a
    * tenth of a second under 10s (e.g. `0:09.3`) where it matters; clamped at
    * `0:00`.
    */
  def formatClock(ms: Long): String =
    val clamped = math.max(0L, ms)
    val totalSecs = clamped / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    if clamped < 10000 then
      val tenths = (clamped % 1000) / 100
      f"$mins%d:$secs%02d.$tenths%d"
    else f"$mins%d:$secs%02d"

  /** Displayed remaining ms for `side` ("white"/"black"), given the last server
    * push (`clock`) and how long ago it was received. Only the side the clock is
    * `runningFor` ticks down locally (the server stays authoritative and
    * overwrites on the next push); clamped at zero. The client never flags.
    */
  def clockRemainingMs(
      clock: ClockDto,
      side: String,
      elapsedSinceReceiptMs: Long
  ): Long =
    val base = if side == "white" then clock.whiteMs else clock.blackMs
    if clock.runningFor.contains(side) then
      math.max(0L, base - elapsedSinceReceiptMs)
    else base

  /** Whether `side`'s clock should show the low-time (urgency) treatment — under
    * ten seconds and still running.
    */
  def clockIsUrgent(clock: ClockDto, side: String, remainingMs: Long): Boolean =
    clock.runningFor.contains(side) && remainingMs < 10000L

  // -- Analysis (post-game move quality) -------------------------------------

  /** White-relative eval (centipawns) → display text: "+1.5", "-2.0", or a
    * mate marker for a (near-)mate score. */
  def evalText(evalCp: Int): String =
    if evalCp >= 90000 then "#"
    else if evalCp <= -90000 then "-#"
    else
      val pawns = evalCp / 100.0
      val s = f"$pawns%.1f"
      if pawns >= 0 then s"+$s" else s

  /** White's share (0–100) of the eval bar, from the white-relative win%. */
  def evalBarWhitePct(winPct: Double): Double =
    math.max(0.0, math.min(100.0, winPct))

  /** CSS modifier for a NAG glyph — drives `.move-glyph-<class>` colouring. */
  def glyphClass(glyph: Option[String]): String =
    glyph match
      case Some("!!") => "brilliant"
      case Some("!")  => "good"
      case Some("!?") => "interesting"
      case Some("?!") => "inaccuracy"
      case Some("?")  => "mistake"
      case Some("??") => "blunder"
      case _          => ""

  /** Per-move analysis for the half-move at flat index `i` (by ply). */
  def analysisForMove(
      analysis: Option[GameAnalysisDto],
      i: Int
  ): Option[MoveAnalysisDto] =
    analysis.flatMap(_.moves.find(_.ply == i))

  /** Analysis of the move that produced the shown replay frame (`activePly`:
    * 0 = initial → no move; N → the (N-1)-th half-move). Keeps the analysis
    * panels in lock-step with the replay scrubber. */
  def analysisAtPly(
      analysis: Option[GameAnalysisDto],
      activePly: Int
  ): Option[MoveAnalysisDto] =
    if activePly <= 0 then None else analysisForMove(analysis, activePly - 1)

  /** Accuracy percentage as display text, e.g. "92.4%". */
  def accuracyText(pct: Double): String = f"$pct%.1f%%"

  /** Display label for an opening: "B90 · Sicilian Defense: Najdorf", or just
    * the name when there's no ECO code. */
  def openingLabel(opening: OpeningDto): String =
    opening.eco.fold(opening.name)(code => s"$code · ${opening.name}")

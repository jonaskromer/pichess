package chess.bot.lichess

import zio.*

import chess.bot.engine.{GamePhase, Search, TimeManager}
import chess.model.rules.MoveValidator

/** Top-level orchestrator: subscribe to the Lichess account event stream,
  * dispatch each event, and spawn a per-game fiber for every accepted
  * [[AccountEvent.GameStart]].
  *
  * The Bridge is intentionally tiny — almost all the decision logic lives in
  * [[GameRunner]] (pure, fully unit-tested) and the Lichess I/O lives in
  * [[BotApiClient]] (interface). Bridge just glues them:
  *   - account events → accept-or-fork-game dispatch
  *   - per-game events → fold through GameRunner.decide, perform the resulting
  *     [[GameRunner.Action]]
  *
  * Per-game fibers are spawned with [[ZIO.forkDaemon]] so the parent (the
  * account-event loop) doesn't block waiting for games to finish. The fibers
  * terminate naturally when the per-game NDJSON stream closes (Lichess sends
  * the final state then EOFs).
  */
object Bridge:

  /** Acceptance policy — standard chess only (variants like chess960, atomic,
    * antichess, … have rules our engine doesn't implement), and rated only:
    * casual games still burn the daily game cap but don't move the rating, so
    * accepting them would waste capacity.
    */
  def shouldAccept(c: ChallengeInfo): Boolean =
    c.variant.key == "standard" && c.rated

  /** Run the top-level event loop forever. Returns only on stream failure
    * (which the caller usually wraps in `.retry(Schedule.fixed(5.seconds))`).
    *
    * `botUsername` is matched against the per-game `white`/`black` player
    * records to determine which colour we're playing — caseinsensitive.
    * `searchDepth` is the fixed search depth (iterative-deepening + time budget
    * come in a later phase).
    */
  def run(
      botUsername: String,
      searchFactory: () => Search,
      searchDepth: Int,
      api: BotApiClient
  ): IO[Throwable, Unit] =
    api.streamEvents.runForeach { event =>
      dispatchAccountEvent(event, botUsername, searchFactory, searchDepth, api)
    }

  /** Account-level dispatch: accept compatible challenges, fork per-game
    * fibers, ignore the rest (cancellations / declines are informational).
    */
  private def dispatchAccountEvent(
      event: AccountEvent,
      botUsername: String,
      searchFactory: () => Search,
      searchDepth: Int,
      api: BotApiClient
  ): IO[Throwable, Unit] =
    event match
      case AccountEvent.Challenge(c) if shouldAccept(c) =>
        api
          .acceptChallenge(c.id)
          .catchAll(err =>
            ZIO.logWarning(
              s"Failed to accept challenge ${c.id}: ${err.getMessage}"
            )
          )
      case AccountEvent.GameStart(g) =>
        runGame(
          g.id,
          botUsername,
          searchFactory,
          searchDepth,
          api
        ).forkDaemon.unit
      case _ =>
        ZIO.unit

  /** One per-game fiber: consume the game stream, drive [[GameRunner.decide]]
    * through each event, perform the resulting action. Catches the per-stream
    * failure so it doesn't propagate to the account loop.
    */
  private[lichess] def runGame(
      gameId: String,
      botUsername: String,
      searchFactory: () => Search,
      searchDepth: Int,
      api: BotApiClient
  ): UIO[Unit] =
    // Fresh, ISOLATED search per game (its own TT + killer/history tables) —
    // no per-search mutable state is shared across concurrent games; the only
    // shared structure is the global LazySMP helper budget, which is
    // thread-safe.
    val search = searchFactory()
    api
      .streamGame(gameId)
      .runFoldZIO(Option.empty[GameRunner.State]) { (prev, event) =>
        val (next, action) = GameRunner.decide(event, botUsername, prev)
        handleAction(action, gameId, search, searchDepth, api).as(next)
      }
      .unit
      .catchAllCause(c => ZIO.logErrorCause(s"Game $gameId stream failed", c))

  /** Convert a [[GameRunner.Action]] into the Lichess HTTP call it implies.
    * `MoveFrom` runs the search and POSTs the chosen move.
    *
    * We deliberately **never resign**. A competing bot should make the opponent
    * prove the win — auto-resigning the moment the search comes back empty
    * turned us into a "surrender-monkey" (every non-won game ended in
    * resignation). When the search returns no move it means our reconstructed
    * board has no legal move: either the game is genuinely over (the server has
    * already ended it, so there's nothing to do) or it's a transient board
    * hiccup (resigning would needlessly throw a live game). Either way we log
    * and wait for the next event rather than concede.
    */
  private def handleAction(
      action: GameRunner.Action,
      gameId: String,
      search: Search,
      searchDepth: Int,
      api: BotApiClient
  ): IO[Throwable, Unit] =
    action match
      case GameRunner.Action.MoveFrom(state, ourTimeMs, ourIncMs, oppTimeMs) =>
        // Clock-aware, ADAPTIVE budget: sizes each search to the time left and
        // spends our banked surplus where it matters — game stage (middlegame),
        // clock advantage (we're ahead), and checks (forcing). `searchDepth`
        // is only a fallback floor.
        val budgetMs = TimeManager.budgetMs(
          ourTimeMs,
          ourIncMs,
          oppTimeMs,
          GamePhase.compute(state),
          MoveValidator.isInCheck(state.board, state.activeColor)
        )
        search
          .bestMoveWithBudget(state, budgetMs, fallbackDepth = searchDepth)
          .flatMap {
            case Some(move) =>
              val uci = UciCodec.serialize(move)
              // Per-move log so the adaptive time-management is observable
              // straight from the bot's own log — zero API polling (the per-game
              // NDJSON stream already pushes us the clocks): chosen budget + both
              // sides' remaining clock.
              ZIO.logInfo(
                s"$gameId move $uci  budget=${budgetMs}ms  clock=${ourTimeMs}ms/opp=${oppTimeMs}ms"
              ) *>
                api
                  .makeMove(gameId, uci)
                  .catchAll(err =>
                    ZIO.logWarning(
                      s"Failed to POST move on $gameId: ${err.getMessage}"
                    )
                  )
            case None =>
              ZIO.logWarning(
                s"Search returned no move on $gameId — not resigning; awaiting next event."
              )
          }
      case GameRunner.Action.MalformedEvent(reason) =>
        ZIO.logWarning(s"Malformed event on $gameId: $reason")
      case GameRunner.Action.GameOver =>
        ZIO.logInfo(s"$gameId game over")
      case GameRunner.Action.None =>
        ZIO.unit

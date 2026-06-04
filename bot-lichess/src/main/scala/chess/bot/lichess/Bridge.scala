package chess.bot.lichess

import zio.*

import chess.bot.engine.Search

/** Top-level orchestrator: subscribe to the Lichess account event
  * stream, dispatch each event, and spawn a per-game fiber for every
  * accepted [[AccountEvent.GameStart]].
  *
  * The Bridge is intentionally tiny — almost all the decision logic
  * lives in [[GameRunner]] (pure, fully unit-tested) and the Lichess
  * I/O lives in [[BotApiClient]] (interface). Bridge just glues them:
  *   - account events → accept-or-fork-game dispatch
  *   - per-game events → fold through GameRunner.decide, perform the
  *     resulting [[GameRunner.Action]]
  *
  * Per-game fibers are spawned with [[ZIO.forkDaemon]] so the parent
  * (the account-event loop) doesn't block waiting for games to finish.
  * The fibers terminate naturally when the per-game NDJSON stream
  * closes (Lichess sends the final state then EOFs).
  */
object Bridge:

  /** Acceptance policy — Phase 2 only accepts standard chess. Variants
    * (chess960, atomic, antichess, …) have different rules our engine
    * doesn't implement. Casual / rated both fine. */
  def shouldAccept(c: ChallengeInfo): Boolean =
    c.variant.key == "standard"

  /** Run the top-level event loop forever. Returns only on stream
    * failure (which the caller usually wraps in
    * `.retry(Schedule.fixed(5.seconds))`).
    *
    * `botUsername` is matched against the per-game `white`/`black`
    * player records to determine which colour we're playing —
    * caseinsensitive. `searchDepth` is the fixed search depth
    * (iterative-deepening + time budget come in a later phase). */
  def run(
      botUsername: String,
      search: Search,
      searchDepth: Int,
      api: BotApiClient,
  ): IO[Throwable, Unit] =
    api.streamEvents.runForeach { event =>
      dispatchAccountEvent(event, botUsername, search, searchDepth, api)
    }

  /** Account-level dispatch: accept compatible challenges, fork
    * per-game fibers, ignore the rest (cancellations / declines are
    * informational). */
  private def dispatchAccountEvent(
      event: AccountEvent,
      botUsername: String,
      search: Search,
      searchDepth: Int,
      api: BotApiClient,
  ): IO[Throwable, Unit] =
    event match
      case AccountEvent.Challenge(c) if shouldAccept(c) =>
        api
          .acceptChallenge(c.id)
          .catchAll(err =>
            ZIO.logWarning(s"Failed to accept challenge ${c.id}: ${err.getMessage}")
          )
      case AccountEvent.GameStart(g) =>
        runGame(g.id, botUsername, search, searchDepth, api).forkDaemon.unit
      case _ =>
        ZIO.unit

  /** One per-game fiber: consume the game stream, drive
    * [[GameRunner.decide]] through each event, perform the resulting
    * action. Catches the per-stream failure so it doesn't propagate to
    * the account loop. */
  private[lichess] def runGame(
      gameId: String,
      botUsername: String,
      search: Search,
      searchDepth: Int,
      api: BotApiClient,
  ): UIO[Unit] =
    api
      .streamGame(gameId)
      .runFoldZIO(Option.empty[GameRunner.State]) { (prev, event) =>
        val (next, action) = GameRunner.decide(event, botUsername, prev)
        handleAction(action, gameId, search, searchDepth, api).as(next)
      }
      .unit
      .catchAllCause(c => ZIO.logErrorCause(s"Game $gameId stream failed", c))

  /** Convert a [[GameRunner.Action]] into the Lichess HTTP call it
    * implies. `MoveFrom` runs the search; if the search finds no move
    * (terminal position the rules engine surprised us at) the bot
    * resigns so the game ends cleanly instead of hanging on our timer. */
  private def handleAction(
      action: GameRunner.Action,
      gameId: String,
      search: Search,
      searchDepth: Int,
      api: BotApiClient,
  ): IO[Throwable, Unit] =
    action match
      case GameRunner.Action.MoveFrom(state) =>
        search.bestMove(state, searchDepth).flatMap {
          case Some(move) =>
            api
              .makeMove(gameId, UciCodec.serialize(move))
              .catchAll(err =>
                ZIO.logWarning(s"Failed to POST move on $gameId: ${err.getMessage}")
              )
          case None =>
            api
              .resign(gameId)
              .catchAll(err =>
                ZIO.logWarning(s"Failed to resign $gameId: ${err.getMessage}")
              )
        }
      case GameRunner.Action.MalformedEvent(reason) =>
        ZIO.logWarning(s"Malformed event on $gameId: $reason")
      case GameRunner.Action.GameOver =>
        ZIO.unit
      case GameRunner.Action.None =>
        ZIO.unit

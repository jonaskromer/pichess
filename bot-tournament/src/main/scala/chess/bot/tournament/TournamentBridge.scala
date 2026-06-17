package chess.bot.tournament

import zio.*

import chess.bot.engine.{GamePhase, Search, TimeManager}
import chess.bot.tournament.TournamentApiClient.GamePlayers
import chess.bot.tournament.TournamentRunner.Action
import chess.codec.UciCodec
import chess.model.piece.Color
import chess.model.rules.MoveValidator

/** Top-level orchestrator for tournament play.
  *
  * Lifecycle: register → join (tolerating "already added by the director") →
  * read the tournament clock for the increment → subscribe to the tournament
  * event stream → spawn a per-game fiber for every
  * [[TournamentEvent.GameStart]].
  *
  * As with the Lichess bridge, the decision logic lives in the pure
  * [[TournamentRunner]] and the I/O in [[TournamentApiClient]]; this glues them
  * together. Per-game fibers are `forkDaemon`ed so the tournament loop doesn't
  * block, and terminate when their NDJSON stream closes (the server ends the
  * stream on `gameEnd`).
  *
  * We deliberately **never resign** (there's no bot resign endpoint anyway):
  * when the search returns no move the position is terminal or transiently
  * unreconstructable — either way we log and await the next event.
  */
object TournamentBridge:

  /** Run the full tournament lifecycle. Returns when the tournament stream
    * completes (`tournamentFinished`); the caller usually wraps it in a
    * reconnect `retry`.
    *
    * @param tournamentId
    *   the tournament to play in
    * @param botName
    *   our registration name (drives our JWT identity)
    * @param fallbackDepth
    *   fixed-depth floor if the budgeted search can't finish even one iteration
    * @param searchFactory
    *   builds a FRESH, isolated search per game (own TT + heuristic tables);
    *   the engine/net are shared
    * @param api
    *   the tournament server client
    */
  def run(
      tournamentId: String,
      botName: String,
      fallbackDepth: Int,
      searchFactory: () => Search,
      api: TournamentApiClient
  ): IO[Throwable, Unit] =
    for
      reg <- api.register(botName)
      _ <- ZIO.logInfo(
        s"Registered with tournament server as ${reg.id} (name='$botName')"
      )
      _ <- api
        .joinTournament(tournamentId)
        .catchAll(err =>
          ZIO.logWarning(
            s"join $tournamentId failed (already added by the director?): ${err.getMessage}"
          )
        )
      info <- api.getTournament(tournamentId)
      incMs = info.clock.increment.toLong * 1000L
      _ <- ZIO.logInfo(
        s"Playing $tournamentId — clock ${info.clock.limit}s + ${info.clock.increment}s/move"
      )
      // gameStart is broadcast (both colours, every game in the round) to every
      // subscriber, so we dedupe by gameId and self-filter by our registered id.
      started <- Ref.make(Set.empty[String])
      done <- api.streamTournament(tournamentId).runForeach { event =>
        dispatchTournamentEvent(
          event,
          tournamentId,
          reg.id,
          started,
          incMs,
          fallbackDepth,
          searchFactory,
          api
        )
      }
    yield done

  /** Tournament-level dispatch: on `gameStart`, work out whether this is one of
    * OUR games and as which colour (the broadcast `color` is meaningless — see
    * [[resolveOurColor]]), then fork a per-game fiber. Other events are
    * informational.
    */
  private[tournament] def dispatchTournamentEvent(
      event: TournamentEvent,
      tournamentId: String,
      myId: String,
      started: Ref[Set[String]],
      incMs: Long,
      fallbackDepth: Int,
      searchFactory: () => Search,
      api: TournamentApiClient
  ): IO[Throwable, Unit] =
    event match
      case TournamentEvent.GameStart(round, gameId, _) =>
        resolveOurColor(tournamentId, gameId, myId, started, api).flatMap {
          case Some(color) =>
            ZIO.logInfo(s"Game start (round $round): $gameId playing $color") *>
              runGame(
                tournamentId,
                gameId,
                color,
                incMs,
                fallbackDepth,
                searchFactory,
                api
              ).forkDaemon.unit
          case None =>
            ZIO.unit
        }
      case other =>
        ZIO.logInfo(s"Tournament event: $other")

  /** Decide whether to play `gameId` and as which colour.
    *
    * `gameStart` is broadcast with BOTH colours for EVERY game in the round to
    * EVERY subscriber, so we must self-filter: claim the gameId (deduping the
    * two colour events — and any reconnect re-announce), look the game up, and
    * match our registered id against its players. Returns:
    *   - `Some(color)` — it's our game; start playing it (and it stays
    *     claimed);
    *   - `None` on a duplicate, a game we're not in (stays claimed), or a
    *     failed lookup (un-claimed so a later re-announce can retry).
    */
  private[tournament] def resolveOurColor(
      tournamentId: String,
      gameId: String,
      myId: String,
      started: Ref[Set[String]],
      api: TournamentApiClient
  ): IO[Throwable, Option[Color]] =
    started.modify(s => (!s.contains(gameId), s + gameId)).flatMap { fresh =>
      if !fresh then ZIO.succeed(Option.empty[Color])
      else
        api
          .getGame(tournamentId, gameId)
          .foldZIO(
            err =>
              started.update(_ - gameId) *>
                ZIO
                  .logWarning(s"getGame $gameId failed: ${err.getMessage}")
                  .as(Option.empty[Color]),
            players =>
              val color = colorFor(players, myId)
              ZIO
                .when(color.isEmpty)(
                  ZIO.logInfo(s"$gameId is not our game; ignoring")
                )
                .as(color)
          )
    }

  /** Our colour in a game: match our registered id against the two players. */
  private[tournament] def colorFor(
      players: GamePlayers,
      myId: String
  ): Option[Color] =
    if players.white.id == myId then Some(Color.White)
    else if players.black.id == myId then Some(Color.Black)
    else None

  /** One per-game fiber: consume the game stream, drive
    * [[TournamentRunner.decide]] through each event, perform the action.
    *
    * The game stream is **reconnected on failure** (`retry`): a dropped NDJSON
    * connection mustn't end a live game. On reconnect the server re-emits the
    * full `gameState` snapshot, and we recompute the position from the `fen` on
    * every event, so play resumes cleanly; the per-game `search` (with its TT)
    * is created ONCE here and reused across reconnects. Normal completion (the
    * server closes the stream on `gameEnd`) is a success, so `retry` stops. A
    * non-retryable cause (a defect) is caught and logged so the fiber ends
    * gracefully instead of dying silently.
    */
  private[tournament] def runGame(
      tournamentId: String,
      gameId: String,
      ourColor: chess.model.piece.Color,
      incMs: Long,
      fallbackDepth: Int,
      searchFactory: () => Search,
      api: TournamentApiClient,
      reconnectDelay: Duration = 5.seconds
  ): UIO[Unit] =
    // Fresh, ISOLATED search per game (own TT + killer/history tables); only
    // the loaded net and the global LazySMP helper budget are shared, and both
    // are safe across concurrent games.
    val search = searchFactory()
    api
      .streamGame(tournamentId, gameId)
      .runForeach { event =>
        handleAction(
          TournamentRunner.decide(event, ourColor),
          tournamentId,
          gameId,
          search,
          incMs,
          fallbackDepth,
          api
        )
      }
      .tapError(e =>
        ZIO.logWarning(
          s"Game $gameId stream dropped; reconnecting in ${reconnectDelay.toSeconds}s: ${e.getMessage}"
        )
      )
      .retry(Schedule.fixed(reconnectDelay))
      .catchAllCause(c => ZIO.logErrorCause(s"Game $gameId fiber stopped", c))

  /** Convert a [[TournamentRunner.Action]] into the HTTP call it implies.
    * `MoveFrom` sizes the search from the clock (converting NowChess seconds to
    * ms and folding in the tournament increment) and POSTs the chosen move.
    */
  private def handleAction(
      action: Action,
      tournamentId: String,
      gameId: String,
      search: Search,
      incMs: Long,
      fallbackDepth: Int,
      api: TournamentApiClient
  ): IO[Throwable, Unit] =
    action match
      case Action.MoveFrom(state, ourTimeSec, oppTimeSec) =>
        val ourMs = (ourTimeSec * 1000).toLong
        val oppMs = (oppTimeSec * 1000).toLong
        val budgetMs = TimeManager.budgetMs(
          ourMs,
          incMs,
          oppMs,
          GamePhase.compute(state),
          MoveValidator.isInCheck(state.board, state.activeColor)
        )
        search
          .bestMoveWithBudget(state, budgetMs, fallbackDepth = fallbackDepth)
          .flatMap {
            case Some(move) =>
              val uci = UciCodec.serialize(move)
              ZIO.logInfo(
                s"$gameId move $uci  budget=${budgetMs}ms  clock=${ourMs}ms/opp=${oppMs}ms"
              ) *>
                api
                  .makeMove(tournamentId, gameId, uci)
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
      case Action.MalformedEvent(reason) =>
        ZIO.logWarning(s"Malformed event on $gameId: $reason")
      case Action.GameOver =>
        ZIO.logInfo(s"$gameId game over")
      case Action.None =>
        ZIO.unit

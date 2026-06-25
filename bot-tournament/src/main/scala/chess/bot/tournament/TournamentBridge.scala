package chess.bot.tournament

import zio.*

import chess.bot.engine.{GamePhase, Search, TimeManager}
import chess.bot.tournament.TournamentApiClient.GamePlayers
import chess.bot.tournament.TournamentRunner.Action
import chess.codec.UciCodec
import chess.model.piece.Color
import chess.model.rules.MoveValidator
import chess.repository.api.SubmittedMoveDto

/** Per-tournament orchestration for tournament play.
  *
  * Stateless helpers: [[playTournament]] plays ONE tournament (join → read the
  * clock → stream events → fork a per-game fiber for each of OUR games), and
  * the decision logic lives in the pure [[TournamentRunner]] with the I/O in
  * [[TournamentApiClient]]. Registering once and playing MANY tournaments
  * concurrently is [[TournamentManager]]'s job.
  *
  * Per-game fibers are `forkDaemon`ed so the tournament loop doesn't block, and
  * terminate when their NDJSON stream closes (the server ends the stream on
  * `gameEnd`).
  *
  * We deliberately **never resign** (there's no bot resign endpoint anyway):
  * when the search returns no move the position is terminal or transiently
  * unreconstructable — either way we log and await the next event.
  */
object TournamentBridge:

  /** Join and play ONE tournament: join (tolerating "already added by the
    * director"), read the clock for the increment, then stream its events and
    * fork a per-game fiber for each of our games. One pass — the
    * [[TournamentManager]] wraps this with reconnect-retry. Returns when the
    * tournament stream completes.
    *
    * @param tournamentId
    *   the tournament to play in
    * @param myId
    *   our registered bot id (matched against game players)
    * @param fallbackDepth
    *   fixed-depth floor if the budgeted search can't finish even one iteration
    * @param searchFactory
    *   builds a FRESH, isolated search per game (own TT + heuristic tables);
    *   the engine/net are shared
    * @param api
    *   the tournament server client
    */
  private[tournament] def playTournament(
      tournamentId: String,
      myId: String,
      fallbackDepth: Int,
      searchFactory: () => Search,
      api: TournamentApiClient,
      recorder: Option[GameRecorder] = None
  ): IO[Throwable, Unit] =
    for
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
          myId,
          started,
          incMs,
          fallbackDepth,
          searchFactory,
          api,
          recorder
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
      api: TournamentApiClient,
      recorder: Option[GameRecorder]
  ): IO[Throwable, Unit] =
    event match
      case TournamentEvent.GameStart(round, gameId, _) =>
        resolveOurColor(tournamentId, gameId, myId, started, api).flatMap {
          case Some((color, opponent)) =>
            ZIO.logInfo(
              s"Game start (round $round): $gameId playing $color vs ${opponent.name}"
            ) *>
              TournamentMetrics.gameStarted(opponent.name, color) *>
              runGame(
                tournamentId,
                gameId,
                color,
                opponent.name,
                incMs,
                fallbackDepth,
                searchFactory,
                api,
                recorder = recorder
              ).forkDaemon.unit
          case None =>
            ZIO.unit
        }
      case TournamentEvent.Heartbeat =>
        ZIO.unit // keep-alive; silently ignored (arrives every ~10s)
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
  ): IO[Throwable, Option[(Color, BotRef)]] =
    started.modify(s => (!s.contains(gameId), s + gameId)).flatMap { fresh =>
      if !fresh then ZIO.succeed(Option.empty[(Color, BotRef)])
      else
        api
          .getGame(tournamentId, gameId)
          .foldZIO(
            err =>
              started.update(_ - gameId) *>
                ZIO
                  .logWarning(s"getGame $gameId failed: ${err.getMessage}")
                  .as(Option.empty[(Color, BotRef)]),
            players =>
              val side = ourSide(players, myId)
              ZIO
                .when(side.isEmpty)(
                  ZIO.logInfo(s"$gameId is not our game; ignoring")
                )
                .as(side)
          )
    }

  /** Our colour and the opponent in a game: match our registered id against the
    * two players. `Some((ourColour, opponent))` if we're in it, else `None`.
    */
  private[tournament] def ourSide(
      players: GamePlayers,
      myId: String
  ): Option[(Color, BotRef)] =
    if players.white.id == myId then Some((Color.White, players.black))
    else if players.black.id == myId then Some((Color.Black, players.white))
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
      opponent: String,
      incMs: Long,
      fallbackDepth: Int,
      searchFactory: () => Search,
      api: TournamentApiClient,
      reconnectDelay: Duration = 5.seconds,
      recorder: Option[GameRecorder] = None
  ): UIO[Unit] =
    // Fresh, ISOLATED search per game (own TT + killer/history tables); only
    // the loaded net and the global LazySMP helper budget are shared, and both
    // are safe across concurrent games.
    val search = searchFactory()
    // `gameOpened`/`gameClosed` bracket the live-games gauge so it's correct
    // however the fiber ends (completion, interruption, defect). `moves` (the
    // running UCI log, for opening + length) and `finished` (emit-once guard)
    // are created ONCE so they survive stream reconnects.
    ZIO.acquireReleaseWith(TournamentMetrics.gameOpened)(_ =>
      TournamentMetrics.gameClosed
    ) { _ =>
      for
        moves    <- Ref.make("")
        finished <- Ref.make(false)
        recorded <- Ref.make(Vector.empty[SubmittedMoveDto])
        ctx = GameContext(gameId, ourColor, opponent, moves, finished, recorded, recorder)
        played   <- api
          .streamGame(tournamentId, gameId)
          .runForeach { event =>
            recordGameEvent(event, ctx) *>
              handleAction(
                TournamentRunner.decide(event, ourColor),
                tournamentId,
                gameId,
                search,
                incMs,
                fallbackDepth,
                ourColor,
                api
              )
          }
          .tapError(e =>
            TournamentMetrics.reconnect *>
              ZIO.logWarning(
                s"Game $gameId stream dropped; reconnecting in ${reconnectDelay.toSeconds}s: ${e.getMessage}"
              )
          )
          .retry(Schedule.fixed(reconnectDelay))
          .catchAllCause(c =>
            ZIO.logErrorCause(s"Game $gameId fiber stopped", c)
          )
      yield played
    }

  /** Fold one game event into the per-game metric state: keep the running UCI
    * move log (for opening + length), refresh the clock gauges, count observed
    * moves, and emit the finished-game metrics exactly once on termination
    * (a terminal `gameState` snapshot or the `gameEnd` event, whichever lands
    * first — both can arrive, e.g. snapshot-then-end on a reconnect).
    */
  /** Per-game state threaded through event recording: the running UCI log
    * (`moves`, for opening + length), the emit-once `finished` guard, the
    * accumulated `recorded` half-moves (UCI + clock, for the archive), and the
    * optional `recorder` that ships the finished game to the archive store. */
  private[tournament] final case class GameContext(
      gameId: String,
      ourColor: Color,
      opponent: String,
      moves: Ref[String],
      finished: Ref[Boolean],
      recorded: Ref[Vector[SubmittedMoveDto]],
      recorder: Option[GameRecorder]
  )

  private[tournament] def recordGameEvent(
      event: GameEvent,
      ctx: GameContext
  ): UIO[Unit] =
    event match
      case GameEvent.StateSnapshot(_, log, _, clock, status, winner) =>
        ctx.moves.set(log) *> TournamentMetrics.clocks(clock) *>
          ZIO.when(isTerminal(status))(emitFinished(winner, status, ctx)).unit
      case GameEvent.MovePlayed(uci, _, turn, clock) =>
        ctx.moves.update(Openings.append(_, uci)) *>
          ctx.recorded.update(
            _ :+ SubmittedMoveDto(uci, Some(TournamentRecorder.moverClockMs(clock, turn)), None)
          ) *>
          TournamentMetrics.clocks(clock) *>
          TournamentMetrics.moveObserved(turn.opposite)
      case GameEvent.GameEnded(winner, status) =>
        emitFinished(winner, status, ctx)
      case GameEvent.Heartbeat =>
        ZIO.unit

  /** A `gameState` status that is neither `ongoing` nor `pending` is terminal
    * (checkmate/stalemate/draw/resigned/timeout) — same partition
    * [[TournamentRunner.decide]] uses to map a snapshot to `GameOver`.
    */
  private def isTerminal(status: String): Boolean =
    status != "ongoing" && status != "pending"

  /** Emit the per-game outcome metrics once (guarded by `finished`): result ×
    * termination, opening family, first move, and game length.
    */
  private[tournament] def emitFinished(
      winner: Option[Color],
      status: String,
      ctx: GameContext
  ): UIO[Unit] =
    ctx.finished.getAndSet(true).flatMap { already =>
      ZIO
        .unless(already) {
          ctx.moves.get.flatMap { log =>
            val outcome = GameOutcome.classify(winner, status, ctx.ourColor)
            TournamentMetrics.gameFinished(ctx.opponent, ctx.ourColor, outcome) *>
              TournamentMetrics.opening(ctx.opponent, Openings.family(log)) *>
              TournamentMetrics.gameLength(Openings.plies(log)) *>
              ZIO.foreachDiscard(Openings.firstMove(log))(
                TournamentMetrics.firstMove
              ) *>
              archiveGame(winner, ctx)
          }
        }
        .unit
    }

  /** Ship the finished game to the archive store, if a recorder is configured.
    * Best-effort: errors are the recorder's concern (it swallows them). */
  private def archiveGame(winner: Option[Color], ctx: GameContext): UIO[Unit] =
    ZIO.foreachDiscard(ctx.recorder) { rec =>
      ctx.recorded.get.flatMap { moves =>
        rec.sink(
          TournamentRecorder.submission(
            ctx.gameId,
            rec.botName,
            ctx.ourColor,
            ctx.opponent,
            winner,
            moves
          )
        )
      }
    }

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
      ourColor: Color,
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
        for
          start <- Clock.nanoTime
          best  <- search.bestMoveWithBudget(
            state,
            budgetMs,
            fallbackDepth = fallbackDepth
          )
          end <- Clock.nanoTime
          _   <- TournamentMetrics.thinkTime(
            ourColor,
            (end - start).toDouble / 1.0e9
          )
          _ <- TournamentMetrics.budgetSeconds(budgetMs.toDouble / 1000.0)
          posted <- best match
            case Some(move) =>
              val uci = UciCodec.serialize(move)
              ZIO.logInfo(
                s"$gameId move $uci  budget=${budgetMs}ms  clock=${ourMs}ms/opp=${oppMs}ms"
              ) *>
                api
                  .makeMove(tournamentId, gameId, uci)
                  .catchAll(err =>
                    TournamentMetrics.moveFailed *>
                      ZIO.logWarning(
                        s"Failed to POST move on $gameId: ${err.getMessage}"
                      )
                  )
            case None =>
              TournamentMetrics.searchNoMove *>
                ZIO.logWarning(
                  s"Search returned no move on $gameId — not resigning; awaiting next event."
                )
        yield posted
      case Action.MalformedEvent(reason) =>
        ZIO.logWarning(s"Malformed event on $gameId: $reason")
      case Action.GameOver =>
        ZIO.logInfo(s"$gameId game over")
      case Action.None =>
        ZIO.unit

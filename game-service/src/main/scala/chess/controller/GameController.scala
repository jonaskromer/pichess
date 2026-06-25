package chess.controller

import java.util.concurrent.TimeUnit

import scala.annotation.unused

import zio.*
import zio.stream.SubscriptionRef

import chess.codec.FenSerializer
import chess.events.{GameDomainEvent, GameEventProducer}
import chess.model.board.{DrawReason, GameState, GameStatus}
import chess.model.piece.Color
import chess.model.{ClockState, GameError, SessionState}
import chess.service.{GameMutation, GameService}

/** Controller-level actions on a game session.
  *
  * Each action uses `session.modifyZIO` so that reading the session, mutating
  * the repository, and committing the new session state happen as a single
  * atomic step. `SubscriptionRef` extends `Ref.Synchronized`, which holds a
  * semaphore during the effect — concurrent callers (e.g. TUI + Web) queue on
  * it rather than racing. If the effect fails, the session is left unchanged,
  * so a failed `makeMove` doesn't corrupt history.
  *
  * Every method takes a `GameEventProducer` and publishes the corresponding
  * Kafka event (`Undone`, `Redone`, `DrawClaimed`, `Forfeited`) after the state
  * mutation is persisted. `gs.makeMove` already publishes its own `MoveMade`
  * event; the only additional event the controller emits on `makeMove` is a
  * `DrawClaimed(FivefoldRepetition)` when auto-draw triggers.
  */
object GameController:

  /** Halfmove clock value at which the 50-move draw rule can be claimed. */
  val FiftyMoveThreshold: Int = 100

  /** Number of times a position must occur for a threefold repetition claim. */
  val ThreefoldThreshold: Int = 3

  /** Number of times a position must occur for an automatic fivefold draw. */
  val FivefoldThreshold: Int = 5

  private val now: UIO[Long] = Clock.currentTime(TimeUnit.MILLISECONDS)

  def makeMove(
      gs: GameService,
      // Publishing flows through `gs.commit`, so `makeMove` doesn't touch
      // `producer` directly — kept for signature parity with the other
      // controller actions (undo/redo/claimDraw/forfeit) that do.
      @unused producer: GameEventProducer,
      session: SubscriptionRef[SessionState],
      rawInput: String
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      gs.makeMove(s.gameId, rawInput).flatMap { (event, mutation) =>
        val provisional =
          s.game.recordMove(event.move, mutation.state, event.san)
        val triggeredFivefold =
          mutation.state.status.isPlaying && isFivefoldRepetition(provisional)
        // When fivefold triggers, amend the mutation: state becomes the
        // drawn state AND an extra `DrawClaimed` event is appended.
        // When it doesn't, keep `provisional` (no `.copy`) and the
        // original mutation. `commit` then does a single save + a
        // single publish per move, regardless of branch.
        val finalize: IO[GameError, (chess.model.GameSnapshot, GameMutation)] =
          if triggeredFivefold then
            val drawState =
              mutation.state.endWith(
                GameStatus.Draw(DrawReason.FivefoldRepetition)
              )
            for ts <- now
            yield
              val drawEvent = GameDomainEvent.DrawClaimed(
                gameId = s.gameId,
                resultingFen = FenSerializer.serialize(drawState),
                reason = DrawReason.FivefoldRepetition.toString,
                occurredAt = ts
              )
              (
                provisional.replaceHead(drawState),
                mutation.amend(_ => Some((drawState, drawEvent)))
              )
          else ZIO.succeed((provisional, mutation))
        finalize.flatMap { (finalGame, finalMutation) =>
          for
            _ <- gs.commit(finalMutation)
            nowMs <- now
          yield
            // Timed game: bank the mover's elapsed time + increment and start
            // the opponent's clock — or pause it if this move ended the game.
            val newClock = s.clock.map(
              advanceClock(
                _,
                s.state.activeColor,
                finalGame.state.status.isOver,
                nowMs
              )
            )
            (
              (),
              s.copy(
                game = finalGame,
                error = None,
                output = None,
                clock = newClock
              )
            )
        }
      }
    }

  /** Advance the clock after a completed move by `mover`: bank its elapsed time
    * + increment and start the opponent's clock — but if the move ended the
    * game, pause instead (there's no opponent to move).
    */
  private def advanceClock(
      clock: ClockState,
      mover: Color,
      gameOver: Boolean,
      now: Long
  ): ClockState =
    val advanced = clock.afterMove(mover, now)
    if gameOver then advanced.copy(runningSince = None) else advanced

  /** End the game on time when the side to move has flagged as of `nowMs`. The
    * game-service is the authoritative clock; the timeout daemon in
    * [[chess.gameservice.GrpcServer]] calls this on a tick. Returns `true` when
    * it flagged (the session now holds a `Timeout` terminal + a stopped clock).
    * No-op (`false`) for untimed games, already-over games, or a clock that
    * hasn't run out — so a redundant tick is harmless.
    */
  def flagIfTimedOut(
      gs: GameService,
      producer: GameEventProducer,
      session: SubscriptionRef[SessionState],
      nowMs: Long
  ): IO[GameError, Boolean] =
    session.modifyZIO { s =>
      s.clock match
        case Some(clock)
            if !s.state.status.isOver &&
              clock.flagged(s.state.activeColor, nowMs) =>
          val winner = s.state.activeColor.opposite
          val timedOut = s.state.endWith(GameStatus.Timeout(winner))
          for
            _ <- gs.saveState(s.gameId, timedOut)
            _ <- producer.publish(
              GameDomainEvent.GameEnded(
                gameId = s.gameId,
                resultingFen = FenSerializer.serialize(timedOut),
                status = timedOut.status.toString,
                occurredAt = nowMs
              )
            )
          yield (
            true,
            s.copy(
              game = s.game.withCurrentState(timedOut),
              clock = Some(clock.stopped(s.state.activeColor, nowMs)),
              error = None,
              output = None
            )
          )
        case _ => ZIO.succeed((false, s))
    }

  /** Overwrite a session's clock with externally-authoritative values. Used by
    * a NowChess tournament **mirror**, where the upstream server owns the clock:
    * the gateway follower pushes the latest remaining times on every poll so
    * spectators see the live tournament clock on our board.
    *
    * Display-only — unlike [[makeMove]]'s [[advanceClock]] this neither
    * decrements nor banks an increment, and unlike [[flagIfTimedOut]] it never
    * flags or persists; it just replaces the clock so the next SSE frame carries
    * it. `running` starts the side-to-move's clock interpolating in the browser
    * (the upstream is still ticking); pass `false` to freeze it once the
    * upstream game has ended. The increment is irrelevant for a passive mirror,
    * so it's pinned to 0. An already-over mirror is always frozen.
    */
  def setClock(
      session: SubscriptionRef[SessionState],
      whiteMs: Long,
      blackMs: Long,
      running: Boolean,
      nowMs: Long
  ): UIO[Unit] =
    session.update { s =>
      val runningSince =
        if running && !s.state.status.isOver then Some(nowMs) else None
      s.copy(clock = Some(ClockState(whiteMs, blackMs, 0L, runningSince)))
    }

  def undo(
      gs: GameService,
      producer: GameEventProducer,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      s.game.undoOnce match
        case None =>
          ZIO.fail(GameError.InvalidMove("Nothing to undo"))
        case Some(undone) =>
          for
            _ <- gs.saveState(s.gameId, undone.state)
            ts <- now
            _ <- producer.publish(
              GameDomainEvent.Undone(
                gameId = s.gameId,
                resultingFen = FenSerializer.serialize(undone.state),
                occurredAt = ts
              )
            )
          yield ((), s.copy(game = undone, error = None, output = None))
    }

  def redo(
      gs: GameService,
      producer: GameEventProducer,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      s.game.redoOnce match
        case None =>
          ZIO.fail(GameError.InvalidMove("Nothing to redo"))
        case Some(redone) =>
          for
            _ <- gs.saveState(s.gameId, redone.state)
            ts <- now
            _ <- producer.publish(
              GameDomainEvent.Redone(
                gameId = s.gameId,
                resultingFen = FenSerializer.serialize(redone.state),
                occurredAt = ts
              )
            )
          yield ((), s.copy(game = redone, error = None, output = None))
    }

  def claimDraw(
      gs: GameService,
      producer: GameEventProducer,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      if s.state.status.isOver then
        ZIO.fail(GameError.InvalidMove("Game is already over"))
      else
        val fiftyMoveOk = s.state.halfmoveClock >= FiftyMoveThreshold
        val repetitionCount = countCurrentPosition(s.game)
        val threefoldOk = repetitionCount >= ThreefoldThreshold
        if !fiftyMoveOk && !threefoldOk then
          val fiftyMsg =
            val movesPlayed = s.state.halfmoveClock / 2
            val movesLeft = (FiftyMoveThreshold - s.state.halfmoveClock) / 2
            s"50-move: $movesPlayed of ${FiftyMoveThreshold / 2} moves ($movesLeft to go)"
          val repMsg =
            s"repetition: position occurred $repetitionCount of $ThreefoldThreshold times"
          ZIO.fail(
            GameError.InvalidMove(s"Cannot claim draw — $fiftyMsg; $repMsg")
          )
        else
          val reason =
            if threefoldOk then DrawReason.ThreefoldRepetition
            else DrawReason.FiftyMoveRule
          val drawState = s.state.endWith(GameStatus.Draw(reason))
          for
            _ <- gs.saveState(s.gameId, drawState)
            _ <- publishDraw(producer, s.gameId, drawState, reason)
          yield (
            (),
            s.copy(
              game = s.game.replaceHead(drawState),
              error = None,
              output = None
            )
          )
    }

  /** The side to move resigns; the opponent is recorded as the winner.
    *
    * Promotes the current state's status to `Resignation(opponent)` via
    * [[GameState.endWith]], persists the result, and commits the new session.
    * Refuses if the game is already over — you can't resign a finished game.
    */
  def forfeit(
      gs: GameService,
      producer: GameEventProducer,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      if s.state.status.isOver then
        ZIO.fail(GameError.InvalidMove("Game is already over"))
      else
        val winner = s.state.activeColor.opposite
        val resigned = s.state.endWith(GameStatus.Resignation(winner))
        for
          _ <- gs.saveState(s.gameId, resigned)
          ts <- now
          _ <- producer.publish(
            GameDomainEvent.Forfeited(
              gameId = s.gameId,
              resultingFen = FenSerializer.serialize(resigned),
              winner = winner.toString,
              occurredAt = ts
            )
          )
        yield (
          (),
          s.copy(
            game = s.game.withCurrentState(resigned),
            error = None,
            output = None
          )
        )
    }

  private def publishDraw(
      producer: GameEventProducer,
      gameId: String,
      drawState: GameState,
      reason: DrawReason
  ): IO[GameError, Unit] =
    for
      ts <- now
      published <- producer.publish(
        GameDomainEvent.DrawClaimed(
          gameId = gameId,
          resultingFen = FenSerializer.serialize(drawState),
          reason = reason.toString,
          occurredAt = ts
        )
      )
    // `yield published` instead of `yield ()` to dodge scoverage's
    // blind spot for unit-returning for-comp tails — the body is
    // identical, the coverage report just sees this as a statement.
    yield published

  /** Counts how many times the current position has occurred in this game,
    * including the current position itself.
    *
    * Backed by [[GameSnapshot.positionCounts]], an incrementally-maintained
    * Zobrist-keyed map. O(1). Equivalence with the FEN-based implementation is
    * locked down by [[chess.model.rules.RepetitionEquivalenceSpec]] across the
    * full corpus.
    */
  def countCurrentPosition(game: chess.model.GameSnapshot): Int =
    game.countOf(game.state)

  /** Checks whether the current position has occurred five or more times
    * (automatic draw per FIDE rules).
    */
  def isFivefoldRepetition(game: chess.model.GameSnapshot): Boolean =
    countCurrentPosition(game) >= FivefoldThreshold

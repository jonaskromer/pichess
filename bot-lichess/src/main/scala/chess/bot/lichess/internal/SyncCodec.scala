package chess.bot.lichess.internal

import zio.{Runtime, Unsafe}

import chess.codec.FenParserRegex
import chess.model.board.{GameState, Move}
import chess.model.rules.Game

/** Sync bridge over the IO-typed codec + rules layers.
  *
  * Same pattern as the engine's `RulesAdapter`: the lichess bridge receives
  * events on a stream, decides what to do via pure functions, and we don't want
  * to thread ZIO through every decision. This adapter unsafe-runs the IO at the
  * boundary so the decision functions can stay sync.
  *
  * `parseFen` handles the special "startpos" sentinel Lichess uses for the
  * standard starting position — it skips the FEN-parse altogether and hands
  * back the canonical [[GameState.initial]].
  */
private[lichess] object SyncCodec:

  private val runtime = Runtime.default

  /** Sync FEN parse. Returns `Left(msg)` on a malformed FEN. */
  def parseFen(fen: String): Either[String, GameState] =
    if fen == "startpos" then Right(GameState.initial)
    else
      Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(FenParserRegex.parse(fen).either)
          .getOrThrow()
          .left
          .map(_.message)
      }

  /** Sync legal-move application. Returns `None` if the move is illegal at
    * `state` — caller's job to surface that as an error.
    */
  def applyMove(state: GameState, move: Move): Option[GameState] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(Game.applyMove(state, move).either)
        .getOrThrow()
        .toOption
    }

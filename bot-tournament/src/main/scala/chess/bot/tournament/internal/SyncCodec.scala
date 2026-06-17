package chess.bot.tournament.internal

import zio.{Runtime, Unsafe}

import chess.codec.FenParserRegex
import chess.model.board.GameState

/** Sync bridge over the IO-typed codec layer.
  *
  * The tournament bridge receives events on a stream, decides what to do via
  * pure functions, and we don't want to thread ZIO through every decision. This
  * adapter unsafe-runs the FEN parse at the boundary so the decision functions
  * ([[chess.bot.tournament.TournamentRunner]]) can stay sync.
  *
  * Unlike the Lichess bridge, NowChess always sends a real FEN on every event
  * (no `"startpos"` sentinel), so there's no special-case here — and no move
  * replay is needed because each event already carries the post-move FEN.
  */
private[tournament] object SyncCodec:

  private val runtime = Runtime.default

  /** Sync FEN parse. Returns `Left(msg)` on a malformed FEN. */
  def parseFen(fen: String): Either[String, GameState] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(FenParserRegex.parse(fen).either)
        .getOrThrow()
        .left
        .map(_.message)
    }

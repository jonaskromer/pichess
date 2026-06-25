package chess.repository

import zio.*

import chess.codec.{FenSerializer, UciCodec}
import chess.model.board.GameState
import chess.model.piece.Color
import chess.model.{ArchivePly, GameArchive, GameError}
import chess.notation.SanSerializer
import chess.opening.EcoBook
import chess.repository.api.{ArchiveSubmissionDto, SubmittedMoveDto}
import chess.model.rules.Game

/** Builds a [[GameArchive]] from an [[ArchiveSubmissionDto]] (UCI moves +
  * per-move clocks). Replays the UCI from the initial position through the rules
  * engine to derive SAN + resulting FEN per ply, then hands off to
  * [[ArchiveBuilder]] for opening naming + PGN-with-clocks serialization. Used
  * by the repository's archive-ingest route (e.g. the tournament bot POSTs here).
  */
object TournamentArchive:

  def fromSubmission(
      dto: ArchiveSubmissionDto,
      eco: EcoBook
  ): IO[GameError, GameArchive] =
    replay(dto.moves).flatMap { plies =>
      ArchiveBuilder.build(
        dto.gameId,
        dto.source,
        dto.white,
        dto.black,
        plies,
        dto.result,
        finishedAt = 0L,
        eco,
        dto.timeControl
      )
    }

  /** Replay UCI moves from the initial position → an ordered ply list with SAN +
    * resulting FEN, carrying the submitted clocks. */
  private def replay(moves: List[SubmittedMoveDto]): IO[GameError, List[ArchivePly]] =
    ZIO
      .foldLeft(moves.zipWithIndex)((GameState.initial, List.empty[ArchivePly])) {
        case ((state, acc), (m, i)) =>
          for
            move <- ZIO
              .fromEither(UciCodec.parse(m.uci))
              .mapError(e => GameError.ParseError(s"Bad UCI '${m.uci}': $e"))
            san  <- SanSerializer.toSan(move, state)
            next <- Game.applyMove(state, move)
            color = if state.activeColor == Color.White then "white" else "black"
            ply = ArchivePly(
              i,
              color,
              san,
              m.uci,
              FenSerializer.serialize(next),
              occurredAt = 0L,
              m.clockMs,
              m.emtMs
            )
          yield (next, acc :+ ply)
      }
      .map(_._2)

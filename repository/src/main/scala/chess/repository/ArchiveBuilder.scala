package chess.repository

import zio.*

import chess.codec.{FenSerializer, PgnMove, PgnSerializer}
import chess.model.board.GameState
import chess.model.piece.Color
import chess.model.{ArchivePly, GameArchive, GameId}
import chess.opening.EcoBook

/** Assembles a finalized [[GameArchive]] from the accumulated plies: identifies
  * the opening (Phase 2 [[EcoBook]]) and serializes the moves to
  * PGN-with-clocks + `ECO`/`Opening` headers (Phase 1 [[PgnSerializer]]).
  */
object ArchiveBuilder:

  private val initialFen: String = FenSerializer.serialize(GameState.initial)

  def build(
      gameId: GameId,
      source: String,
      white: String,
      black: String,
      plies: List[ArchivePly],
      result: String,
      finishedAt: Long,
      eco: EcoBook,
      timeControl: Option[String]
  ): UIO[GameArchive] =
    val ordered = plies.sortBy(_.ply)
    val opening = eco.identify(ordered.map(_.san))
    val pgnMoves = ordered.map(p =>
      PgnMove(colorOf(p.color), p.san, nag = None, clockMs = p.clockMs, emtMs = p.emtMs)
    )
    val headers =
      List("White" -> white, "Black" -> black) :::
        opening.eco.map("ECO" -> _).toList :::
        ("Opening" -> opening.name) ::
        timeControl.map("TimeControl" -> _).toList
    PgnSerializer.serializeWithResult(pgnMoves, result, headers).map { pgn =>
      GameArchive(
        gameId = gameId,
        source = source,
        white = white,
        black = black,
        result = result,
        timeControl = timeControl,
        initialFen = initialFen,
        plies = ordered,
        openingEco = opening.eco,
        openingName = opening.name,
        openingFamily = opening.family,
        pgn = pgn,
        finishedAt = finishedAt
      )
    }

  private def colorOf(color: String): Color =
    if color == "white" then Color.White else Color.Black

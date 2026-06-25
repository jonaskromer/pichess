package chess.bot.tournament

import zio.*

import chess.model.piece.Color
import chess.repository.api.{ArchiveSubmissionDto, SubmittedMoveDto}

/** Ships finished tournament games to the repository's archive store. `botName`
  * labels our side; `sink` performs the POST (errors swallowed — archiving is
  * best-effort and must never disturb play).
  */
final case class GameRecorder(
    botName: String,
    sink: ArchiveSubmissionDto => UIO[Unit]
)

object TournamentRecorder:

  /** Pure builder for a finished game's archive submission: resolve white/black
    * from our colour, the PGN result token from the (absolute) winner, and pass
    * through the accumulated UCI moves + clocks. `timeControl` is left to the
    * server (the per-move `%clk` carries the clocks). */
  def submission(
      gameId: String,
      botName: String,
      ourColor: Color,
      opponent: String,
      winner: Option[Color],
      moves: Vector[SubmittedMoveDto]
  ): ArchiveSubmissionDto =
    val (white, black) =
      if ourColor == Color.White then (botName, opponent) else (opponent, botName)
    val result = winner match
      case None              => "1/2-1/2"
      case Some(Color.White) => "1-0"
      case Some(Color.Black) => "0-1"
    ArchiveSubmissionDto(
      gameId = gameId,
      source = "tournament",
      white = white,
      black = black,
      result = result,
      timeControl = None,
      moves = moves.toList
    )

  /** Remaining clock (ms) of the side that just moved into `clock`, given the
    * colour to move next (`turn`): the mover is the opposite side. */
  def moverClockMs(clock: GameClock, turn: Color): Long =
    val moverIsWhite = turn == Color.Black
    val seconds = if moverIsWhite then clock.whiteTime else clock.blackTime
    math.max(0L, (seconds * 1000).toLong)

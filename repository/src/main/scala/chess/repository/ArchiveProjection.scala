package chess.repository

import chess.events.GameDomainEvent
import chess.events.GameDomainEvent.{DrawClaimed, Forfeited, GameEnded, MoveMade}
import chess.model.ArchivePly

/** Pure projection from `chess.game-events` to archive rows. Derives a move's
  * 0-based ply index and the colour that moved from its resulting FEN (so the
  * archive upsert is keyed order-free), and maps terminal events to a PGN result
  * token. FEN-field parsing only (side-to-move + fullmove), no full board parse;
  * a standard initial position is assumed (FEN-loaded games may be off by a
  * constant — acceptable for the archive's relative ply ordering).
  */
object ArchiveProjection:

  /** 0-based half-move index of the move that produced `fen`. */
  def plyIndex(fen: String): Option[Int] =
    val parts = fen.trim.split("\\s+")
    if parts.length >= 6 then
      for
        full <- parts(5).toIntOption if full >= 1
        bit  <- sideBit(parts(1))
      yield (full - 1) * 2 + bit - 1
    else None

  /** Number of plies played to reach `fen` (= ply index + 1). */
  def plyCount(fen: String): Option[Int] = plyIndex(fen).map(_ + 1)

  /** The colour that just moved into `fen` (opposite of side-to-move). */
  def mover(fen: String): Option[String] =
    val parts = fen.trim.split("\\s+")
    if parts.length >= 2 then
      parts(1) match
        case "w" => Some("black")
        case "b" => Some("white")
        case _   => None
    else None

  private def sideBit(side: String): Option[Int] = side match
    case "w" => Some(0)
    case "b" => Some(1)
    case _   => None

  /** Archive row for a `MoveMade` (None if its FEN is malformed). Clocks are
    * absent here — `chess.game-events` carries none today; the tournament
    * recorder supplies them on its own path.
    */
  def plyOf(e: MoveMade): Option[ArchivePly] =
    for
      idx <- plyIndex(e.resultingFen)
      col <- mover(e.resultingFen)
    yield ArchivePly(idx, col, e.san, e.moveCoord, e.resultingFen, e.occurredAt, None, None)

  /** PGN result token for a terminal event. */
  def resultToken(event: GameDomainEvent): String =
    event match
      case g: GameEnded =>
        if isDraw(g.status) then "1/2-1/2" else winnerToken(g.resultingFen)
      case f: Forfeited   => colorToken(f.winner)
      case _: DrawClaimed => "1/2-1/2"
      case _              => "*"

  private def isDraw(status: String): Boolean =
    val s = status.toLowerCase
    s.contains("draw") || s.contains("stalemate") || s.contains("repetition") ||
      s.contains("fifty") || s.contains("insufficient") || s.contains("agreement")

  // Checkmate/timeout: the side to move in the final FEN is the loser, so the
  // mover (opposite) is the winner.
  private def winnerToken(fen: String): String =
    mover(fen) match
      case Some("white") => "1-0"
      case Some("black") => "0-1"
      case _             => "*"

  private def colorToken(winner: String): String =
    winner.toLowerCase match
      case "white" => "1-0"
      case "black" => "0-1"
      case _       => "*"

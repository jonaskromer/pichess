package chess.bot.data

import zio.*

import chess.bot.engine.OpeningBook
import chess.model.board.{GameState, Move}
import chess.model.piece.PieceType
import chess.model.rules.Zobrist

/** [[OpeningBook]] backed by a [[BookRepo]] (DuckDB `position_moves`).
  *
  * Built as a thin adapter rather than a direct trait
  * implementation in [[BookRepo]] so the engine module doesn't have
  * to know about a DuckDB-specific type. The engine sees
  * `OpeningBook` (its own trait); bot-data sees both, and provides
  * the bridge.
  *
  * The runtime path is: bot startup → `Db.open` → `BookRepo.duckdb` →
  * `DuckDbOpeningBook.fromRepo(repo)` → handed to
  * `Search.alphaBeta(eval, book)`. The book then sees every
  * `state` the search visits at the root.
  */
object DuckDbOpeningBook:

  /** Construct an [[OpeningBook]] that consults `repo` for every
    * position within `maxPly` half-moves from the start. */
  def fromRepo(repo: BookRepo, maxPly: Int = 24): OpeningBook =
    new RepoBackedBook(repo, maxPly)

  private final class RepoBackedBook(
      repo: BookRepo,
      maxPly: Int,
  ) extends OpeningBook:
    def lookup(state: GameState): UIO[Option[Move]] =
      if OpeningBook.ply(state) >= maxPly then ZIO.none
      else
        repo.bestMove(Zobrist.hash(state)).map(_.flatMap(parseUci))

  /** UCI → Move. Mirrors the parser in `bot-lichess.UciCodec` but
    * lives here to avoid a cross-module dependency just for one
    * tiny function (and so the engine surface stays UCI-agnostic). */
  private def parseUci(uci: String): Option[Move] =
    if uci.length != 4 && uci.length != 5 then None
    else
      for
        from <- parseSquare(uci.substring(0, 2))
        to   <- parseSquare(uci.substring(2, 4))
        promo <-
          if uci.length == 5 then parsePromotion(uci.charAt(4)).map(Some(_))
          else Some(None)
      yield Move(from, to, promo)

  private def parseSquare(s: String): Option[chess.model.board.Position] =
    val col = s.charAt(0)
    val rowChar = s.charAt(1)
    if col < 'a' || col > 'h' || rowChar < '1' || rowChar > '8' then None
    else Some(chess.model.board.Position(col, rowChar - '0'))

  private def parsePromotion(c: Char): Option[PieceType] = c match
    case 'q' => Some(PieceType.Queen)
    case 'r' => Some(PieceType.Rook)
    case 'b' => Some(PieceType.Bishop)
    case 'n' => Some(PieceType.Knight)
    case _   => None

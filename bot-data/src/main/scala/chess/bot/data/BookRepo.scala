package chess.bot.data

import java.sql.Connection

import zio.*

/** Opening-book / move-statistics repository over `position_moves`.
  *
  * The bot reads this at runtime ("at position X, what move have
  * masters played most often?"); the training pipeline writes it
  * ("for each (position, move) seen, increment the {win, draw, loss}
  * counter and accumulate the player Elo for weighting").
  *
  * The "best move" ranking is exposed as a separate method
  * ([[bestMove]]) so the runtime opening book has a tight, indexed
  * point-lookup without having to sort the full row set on every call.
  */
trait BookRepo:

  /** Every recorded move at `zobrist`, in arbitrary order. Used by
    * tooling / debugging; the bot's hot path uses [[bestMove]]. */
  def lookup(zobrist: Long): UIO[List[BookEntry]]

  /** The book's best-move suggestion at `zobrist`, or `None` if the
    * position isn't in the book. Ranks by a weighted score
    * (`2*wins + draws + sum_elo / 1e6`) — wins beat draws, ties
    * broken by aggregate opponent strength so we don't pick moves
    * played mostly by very low-rated players. */
  def bestMove(zobrist: Long): UIO[Option[String]]

  /** Insert / increment a batch of (position, move, outcome) rows.
    * Existing (zobrist, move) pairs accumulate their counters; new
    * pairs are inserted with the given counts. Used by both PGN
    * ingest and the self-play loop. */
  def upsert(rows: Chunk[BookRow]): UIO[Unit]


/** One row of the `position_moves` table. */
final case class BookEntry(
    zobrist: Long,
    moveUci: String,
    wins: Long,
    draws: Long,
    losses: Long,
    sumElo: Long,
)

/** Input shape for [[BookRepo.upsert]] — one record per (position,
  * move) that was just observed once with the given outcome. */
final case class BookRow(
    zobrist: Long,
    moveUci: String,
    wins: Long,
    draws: Long,
    losses: Long,
    sumElo: Long,
)


object BookRepo:

  /** DuckDB-backed implementation. Connection is held for the lifetime
    * of the repo; the caller scopes the surrounding [[Db.open]]. */
  def duckdb(conn: Connection): BookRepo = new DuckDbBookRepo(conn)


  private final class DuckDbBookRepo(conn: Connection) extends BookRepo:

    def lookup(zobrist: Long): UIO[List[BookEntry]] =
      Db.query(
        conn,
        "SELECT zobrist, move_uci, wins, draws, losses, sum_elo " +
          "FROM position_moves WHERE zobrist = ?",
        Seq(zobrist),
      ) { rs =>
        BookEntry(
          zobrist = rs.getLong("zobrist"),
          moveUci = rs.getString("move_uci"),
          wins    = rs.getLong("wins"),
          draws   = rs.getLong("draws"),
          losses  = rs.getLong("losses"),
          sumElo  = rs.getLong("sum_elo"),
        )
      }.orDie

    def bestMove(zobrist: Long): UIO[Option[String]] =
      Db.query(
        conn,
        // Weighted score: a win is worth 2, a draw 1, losses penalise
        // (drop) the move. `sum_elo / 1e6` is a tiebreaker — higher
        // aggregate opponent strength wins.
        "SELECT move_uci FROM position_moves WHERE zobrist = ? " +
          "ORDER BY (2 * wins + draws - losses + sum_elo / 1000000.0) DESC " +
          "LIMIT 1",
        Seq(zobrist),
      )(_.getString("move_uci")).orDie.map(_.headOption)

    /** Upsert via DuckDB's MERGE-equivalent: ON CONFLICT increments
      * counters by the new row's values; otherwise plain INSERT. */
    def upsert(rows: Chunk[BookRow]): UIO[Unit] =
      Db.batchInsert(
        conn,
        // DuckDB supports the PG-flavoured ON CONFLICT … DO UPDATE
        // syntax — composite-key upserts work the same as single-key.
        "INSERT INTO position_moves " +
          "(zobrist, move_uci, wins, draws, losses, sum_elo) " +
          "VALUES (?, ?, ?, ?, ?, ?) " +
          "ON CONFLICT (zobrist, move_uci) DO UPDATE SET " +
          "  wins    = position_moves.wins    + EXCLUDED.wins, " +
          "  draws   = position_moves.draws   + EXCLUDED.draws, " +
          "  losses  = position_moves.losses  + EXCLUDED.losses, " +
          "  sum_elo = position_moves.sum_elo + EXCLUDED.sum_elo",
        rows.toList.map(r =>
          Seq(r.zobrist, r.moveUci, r.wins, r.draws, r.losses, r.sumElo)
        ),
      ).orDie

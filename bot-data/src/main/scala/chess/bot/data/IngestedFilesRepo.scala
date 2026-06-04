package chess.bot.data

import java.sql.{Connection, Timestamp}
import java.time.Instant

import zio.*

/** Tracks which PGN files have been ingested into the training
  * corpus. The single-row guarantee (path PRIMARY KEY) lets the
  * trainer skip files already processed on a previous run — that's
  * the foundation of resumable training: if the process dies mid-
  * corpus, restarting picks up at the next un-processed file.
  *
  * The "atomic per-file" guarantee is provided by wrapping the
  * (ingest + `markIngested`) pair in a transaction at the
  * orchestrator level (see `CorpusTrainer.ingestAll`). Without
  * that, a crash mid-file would let some rows land in
  * `position_moves` / `training_positions` without the marker — on
  * restart, the file would be re-ingested and the partial state
  * would be double-counted.
  */
trait IngestedFilesRepo:

  /** Has this path already been fully ingested? `path` is the
    * absolute filesystem path. Using `Path.toString` (rather than
    * file content hashes) is intentional: if the user replaces a
    * file at the same path with different content, the new content
    * isn't re-ingested. Re-ingesting changed files needs explicit
    * action (delete the row, or use a different DB). */
  def isIngested(path: String): UIO[Boolean]

  /** Mark `path` as fully ingested. Idempotent — re-marking the
    * same path is a no-op (the PRIMARY KEY drops the duplicate
    * insert; the DuckDB error is swallowed). */
  def markIngested(path: String, games: Long): UIO[Unit]

  /** Every path that's been ingested into this DB. Used by the
    * trainer's progress reports + by tooling that wants to compare
    * what's in the corpus against what's on disk. */
  def listIngested: UIO[List[String]]


object IngestedFilesRepo:

  def duckdb(conn: Connection): IngestedFilesRepo = new DuckDbRepo(conn)

  private final class DuckDbRepo(conn: Connection) extends IngestedFilesRepo:

    def isIngested(path: String): UIO[Boolean] =
      Db.query(
        conn,
        "SELECT 1 FROM ingested_files WHERE path = ? LIMIT 1",
        Seq(path),
      )(_.getInt(1)).orDie.map(_.nonEmpty)

    def markIngested(path: String, games: Long): UIO[Unit] =
      // INSERT OR IGNORE semantics via ON CONFLICT — same row
      // doesn't get duplicated. Idempotent.
      Db.update(
        conn,
        "INSERT INTO ingested_files (path, ingested_at, games) " +
          "VALUES (?, ?, ?) ON CONFLICT (path) DO NOTHING",
        Seq(path, Timestamp.from(Instant.now()), games),
      ).orDie.unit

    def listIngested: UIO[List[String]] =
      Db.query(
        conn,
        "SELECT path FROM ingested_files ORDER BY path",
      )(_.getString("path")).orDie

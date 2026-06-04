package chess.bot.data

import java.sql.Connection

import zio.*

/** Versioned snapshots of Texel-tuned evaluation weights.
  *
  * Each `version` is an integer monotonically increased per training
  * run; each row is one feature name → its centipawn coefficient.
  * The bot reads `latest` at startup to populate the eval; the tuner
  * writes a new version after a successful run.
  *
  * Versioning matters because we want to be able to roll back a bad
  * weights snapshot (a tuner glitch that ships weaker weights would
  * otherwise be one-way). The bot's eval consumer chooses to read
  * either a specific version or the highest one.
  */
trait WeightsRepo:

  /** The most recent weight snapshot. `None` if no tuner run has
    * landed yet — the bot then falls back to its hand-coded defaults. */
  def latest: UIO[Option[WeightSnapshot]]

  /** Load a specific version. Used for A/B comparisons and rollback
    * (the SelfPlay loop can run "this version vs version-1" to verify
    * the new snapshot actually improves over the previous one). */
  def load(version: Int): UIO[Option[WeightSnapshot]]

  /** Save a new snapshot. Fails if `version` already exists — a
    * previous run shouldn't be silently overwritten. */
  def save(snapshot: WeightSnapshot): UIO[Unit]


/** One versioned snapshot — a flat Map keyed by feature name. */
final case class WeightSnapshot(version: Int, weights: Map[String, Int])


object WeightsRepo:

  def duckdb(conn: Connection): WeightsRepo = new DuckDbWeightsRepo(conn)


  private final class DuckDbWeightsRepo(conn: Connection) extends WeightsRepo:

    def latest: UIO[Option[WeightSnapshot]] =
      Db.query(
        conn,
        "SELECT MAX(version) AS v FROM eval_weights",
      )(rs => Option(rs.getObject("v")).map(_ => rs.getInt("v"))).orDie
        .map(_.headOption.flatten)
        .flatMap {
          case Some(v) => load(v)
          case None    => ZIO.succeed(None)
        }

    def load(version: Int): UIO[Option[WeightSnapshot]] =
      Db.query(
        conn,
        "SELECT feature_name, value FROM eval_weights WHERE version = ?",
        Seq(version),
      )(rs => (rs.getString("feature_name"), rs.getInt("value")))
        .orDie
        .map { rows =>
          if rows.isEmpty then None
          else Some(WeightSnapshot(version, rows.toMap))
        }

    def save(snapshot: WeightSnapshot): UIO[Unit] =
      Db.batchInsert(
        conn,
        "INSERT INTO eval_weights (version, feature_name, value) VALUES (?, ?, ?)",
        snapshot.weights.toList.map { case (name, value) =>
          Seq(snapshot.version, name, value)
        },
      ).orDie

package chess.bot.data

import java.sql.Connection

import zio.*
import zio.stream.*

/** Texel-tuner input table. One row per sampled position: the Zobrist
  * key, the outcome label (1.0 = side-to-move won, 0.5 = draw,
  * 0.0 = lost), and a `quiet` flag (true when the position has no
  * pending capture / check — Texel tuning needs quiet positions to
  * avoid noise from in-flight tactics that the static eval can't see).
  */
trait TrainingRepo:

  /** Stream every quiet training row. Used by the tuner which needs
    * one pass over the data per coordinate-descent step (~10-20
    * passes total for convergence).
    *
    * Streamed (not materialised) so the tuner can fold over the data
    * without loading the full corpus into memory — millions of rows
    * fit on disk fine, less so on heap. */
  def streamQuiet: ZStream[Any, Throwable, TrainingRow]

  /** Append a batch of training rows. Called by PGN ingest at corpus
    * build time and by SelfPlay at every iteration. */
  def appendBatch(rows: Chunk[TrainingRow]): UIO[Unit]

  /** Total row count. Used by tuner reports and as a sanity check
    * (a tuner run that thinks there are zero training rows fails
    * very quickly otherwise). */
  def count: UIO[Long]


/** A labelled training position. `outcome` is from the side-to-move
  * perspective, mapped from the eventual game result:
  *   - 1.0  → side-to-move side won
  *   - 0.5  → draw
  *   - 0.0  → side-to-move side lost
  *
  * `quiet` is set during ingest by a cheap heuristic (no captures or
  * checks in the next ply). The tuner can filter further at query
  * time if it wants stricter quietness.
  *
  * `weight` is the source-quality multiplier (0.0–1.0). Curated
  * corpora (PGN Mentor master archives) get weight 1.0; tournament
  * dumps (TWIC) get ~0.7; mixed-Elo public dumps (Lichess) get ~0.3.
  * The tuner multiplies its per-row loss contribution by this weight
  * so high-quality data dominates the gradient direction.
  */
final case class TrainingRow(
    zobrist: Long,
    outcome: Float,
    quiet: Boolean,
    weight: Float = 1.0f,
    // Material-difference features (white count − black count) for
    // each piece type. Pre-computed at ingest so the tuner doesn't
    // need to look up FENs and re-extract features per row. Schema
    // grows to add more features (PST, mobility) when those land.
    pawnDiff:   Int = 0,
    knightDiff: Int = 0,
    bishopDiff: Int = 0,
    rookDiff:   Int = 0,
    queenDiff:  Int = 0,
)


object TrainingRepo:

  def duckdb(conn: Connection): TrainingRepo = new DuckDbTrainingRepo(conn)


  private final class DuckDbTrainingRepo(conn: Connection) extends TrainingRepo:

    def streamQuiet: ZStream[Any, Throwable, TrainingRow] =
      // ZStream.fromIterableZIO materialises the result list once.
      // Adequate for Phase 5: even 10M rows × 16 bytes/row = 160 MB,
      // fits in heap with margin. If we ever exceed that, switch to
      // a chunked-cursor variant via `setFetchSize` + iterator.
      ZStream.fromIterableZIO(
        Db.query(
          conn,
          "SELECT zobrist, outcome, weight, " +
            "pawn_diff, knight_diff, bishop_diff, rook_diff, queen_diff " +
            "FROM training_positions WHERE quiet = TRUE",
        ) { rs =>
          TrainingRow(
            zobrist    = rs.getLong("zobrist"),
            outcome    = rs.getFloat("outcome"),
            quiet      = true,
            weight     = rs.getFloat("weight"),
            pawnDiff   = rs.getInt("pawn_diff"),
            knightDiff = rs.getInt("knight_diff"),
            bishopDiff = rs.getInt("bishop_diff"),
            rookDiff   = rs.getInt("rook_diff"),
            queenDiff  = rs.getInt("queen_diff"),
          )
        }
      )

    def appendBatch(rows: Chunk[TrainingRow]): UIO[Unit] =
      Db.batchInsert(
        conn,
        "INSERT INTO training_positions " +
          "(zobrist, outcome, quiet, weight, " +
          " pawn_diff, knight_diff, bishop_diff, rook_diff, queen_diff) " +
          "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows.toList.map(r =>
          Seq(
            r.zobrist, r.outcome, r.quiet, r.weight,
            r.pawnDiff, r.knightDiff, r.bishopDiff, r.rookDiff, r.queenDiff,
          )
        ),
      ).orDie

    def count: UIO[Long] =
      Db.query(conn, "SELECT COUNT(*) AS n FROM training_positions")(
        _.getLong("n")
      ).orDie.map(_.headOption.getOrElse(0L))

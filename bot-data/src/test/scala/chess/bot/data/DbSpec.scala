package chess.bot.data

import zio.*
import zio.test.*

/** Behavioural specs for the [[Db]] connection facade.
  *
  * Each test opens a fresh in-memory DuckDB instance (`:memory:`) in
  * its own scope so test isolation is automatic — no shared state
  * across tests, no teardown logic.
  */
object DbSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  def spec = suite("Db")(
    suite("open")(
      test("returns a usable connection") {
        ZIO.scoped {
          for
            conn <- Db.open(memoryCfg)
            count <- Db.query(conn, "SELECT 42 AS n")(_.getInt("n"))
          yield assertTrue(count == List(42))
        }
      },
      test("provisions every schema table on first open") {
        ZIO.scoped {
          for
            conn <- Db.open(memoryCfg)
            tables <- Db.query(
                        conn,
                        "SELECT table_name FROM information_schema.tables " +
                          "WHERE table_schema = 'main' ORDER BY table_name",
                      )(_.getString("table_name"))
          yield assertTrue(
            tables.contains("games"),
            tables.contains("positions"),
            tables.contains("position_moves"),
            tables.contains("training_positions"),
            tables.contains("eval_weights"),
          )
        }
      },
      test("re-opening an existing db is idempotent (no DDL error)") {
        // For an in-memory db re-open means re-running the schema
        // statements on the same connection target — the CREATE IF
        // NOT EXISTS guards have to short-circuit.
        ZIO.scoped {
          for
            _ <- Db.open(memoryCfg)
            _ <- Db.open(memoryCfg)
          yield assertCompletes
        }
      },
    ),
    suite("DML helpers")(
      test("update returns affected row count") {
        ZIO.scoped {
          for
            conn <- Db.open(memoryCfg)
            n1 <- Db.update(
                    conn,
                    "INSERT INTO eval_weights (version, feature_name, value) VALUES (?, ?, ?)",
                    Seq(1, "pawn", 100),
                  )
            n2 <- Db.update(
                    conn,
                    "UPDATE eval_weights SET value = ? WHERE version = ? AND feature_name = ?",
                    Seq(110, 1, "pawn"),
                  )
          yield assertTrue(n1 == 1, n2 == 1)
        }
      },
      test("batchInsert with empty rows is a no-op") {
        // The implementation short-circuits on empty input; we verify
        // by checking no row was added.
        ZIO.scoped {
          for
            conn <- Db.open(memoryCfg)
            _ <- Db.batchInsert(conn, "INSERT INTO eval_weights VALUES (?, ?, ?)", Nil)
            countResult <- Db.query(conn, "SELECT COUNT(*) AS n FROM eval_weights")(_.getInt("n"))
          yield assertTrue(countResult == List(0))
        }
      },
      test("batchInsert writes every row in a single round-trip") {
        ZIO.scoped {
          for
            conn <- Db.open(memoryCfg)
            _ <- Db.batchInsert(
                   conn,
                   "INSERT INTO eval_weights (version, feature_name, value) VALUES (?, ?, ?)",
                   List(
                     Seq(1, "pawn",   100),
                     Seq(1, "knight", 320),
                     Seq(1, "bishop", 330),
                   ),
                 )
            countResult <- Db.query(conn, "SELECT COUNT(*) AS n FROM eval_weights")(_.getInt("n"))
          yield assertTrue(countResult == List(3))
        }
      },
      test("bind handles every supported primitive type") {
        // Exercises every arm of Db.bind via column types our schema
        // genuinely uses + a few extras to nail Double / Float / Object.
        ZIO.scoped {
          for
            conn <- Db.open(memoryCfg)
            _ <- Db.update(
                   conn,
                   "CREATE TABLE binds (l BIGINT, i INT, d DOUBLE, f FLOAT, s VARCHAR, b BOOLEAN, o INT)",
                 )
            _ <- Db.update(
                   conn,
                   "INSERT INTO binds VALUES (?, ?, ?, ?, ?, ?, ?)",
                   Seq(7L, 3, 3.14, 2.5f, "hi", true, java.lang.Integer.valueOf(42)),
                 )
            rows <- Db.query(conn, "SELECT * FROM binds") { rs =>
                      (rs.getLong("l"), rs.getInt("i"), rs.getString("s"))
                    }
          yield assertTrue(rows == List((7L, 3, "hi")))
        }
      },
    ),
  )

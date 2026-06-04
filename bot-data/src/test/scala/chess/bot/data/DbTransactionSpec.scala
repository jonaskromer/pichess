package chess.bot.data

import zio.*
import zio.test.*

/** Behavioural specs for `Db.withTransaction` — commit on success,
  * rollback on failure. The resumable-ingest path relies on these
  * semantics: if anything in the per-file ingest fails, nothing
  * lands in the DB. */
object DbTransactionSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  def spec = suite("Db.withTransaction")(
    test("commits when the action succeeds") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          _    <- Db.update(conn, "CREATE TABLE t (x INT)")
          _    <- Db.withTransaction(conn) {
                    Db.update(conn, "INSERT INTO t VALUES (?)", Seq(42))
                  }
          rows <- Db.query(conn, "SELECT x FROM t")(_.getInt("x"))
        yield assertTrue(rows == List(42))
      }
    },
    test("rolls back when the action fails") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          _    <- Db.update(conn, "CREATE TABLE t (x INT)")
          exit <- Db.withTransaction(conn) {
                    Db.update(conn, "INSERT INTO t VALUES (?)", Seq(99)) *>
                      ZIO.fail(new RuntimeException("simulated crash"))
                  }.exit
          rows <- Db.query(conn, "SELECT x FROM t")(_.getInt("x"))
        yield assertTrue(
          exit.isFailure,
          rows.isEmpty,    // the inserted row was rolled back
        )
      }
    },
    test("restores auto-commit after a successful transaction") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          _    <- Db.update(conn, "CREATE TABLE t (x INT)")
          _    <- Db.withTransaction(conn) {
                    Db.update(conn, "INSERT INTO t VALUES (?)", Seq(1))
                  }
          // Subsequent INSERT without an explicit transaction should
          // auto-commit normally.
          _    <- Db.update(conn, "INSERT INTO t VALUES (?)", Seq(2))
          rows <- Db.query(conn, "SELECT x FROM t ORDER BY x")(_.getInt("x"))
        yield assertTrue(rows == List(1, 2))
      }
    },
    test("restores auto-commit even after a failed transaction") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          _    <- Db.update(conn, "CREATE TABLE t (x INT)")
          _    <- Db.withTransaction(conn) {
                    Db.update(conn, "INSERT INTO t VALUES (?)", Seq(1)) *>
                      ZIO.fail(new RuntimeException("simulated"))
                  }.exit
          // Subsequent INSERT should auto-commit (transaction state
          // was cleaned up despite the failure).
          _    <- Db.update(conn, "INSERT INTO t VALUES (?)", Seq(2))
          rows <- Db.query(conn, "SELECT x FROM t")(_.getInt("x"))
        yield assertTrue(rows == List(2))
      }
    },
  )

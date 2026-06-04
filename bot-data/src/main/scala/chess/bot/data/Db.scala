package chess.bot.data

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.util.Properties

import zio.*

/** Thin JDBC facade over DuckDB.
  *
  * Owns the [[Connection]] lifecycle as a `Scope`-managed resource:
  * each call to [[open]] returns a scoped connection that's closed
  * when the surrounding scope ends. Inside, a small DSL on top of
  * [[PreparedStatement]] / [[ResultSet]] keeps every repository's
  * code free of try/finally + result-set housekeeping while still
  * being plain JDBC underneath — no zio-schema or zio-jdbc 0.1.2
  * version-pinning surprises.
  *
  * DuckDB itself is single-writer; the JDBC connection is
  * thread-safe for a single use-at-a-time pattern. The repos thread
  * effects sequentially via the connection's intrinsic locking;
  * concurrent reads are fine, concurrent writes serialise. That's the
  * right semantics for "training writer / inference reader" in the
  * bot's actual use.
  */
object Db:

  /** Connection settings. `path` is a filesystem path
    * (`./chess-bot.duckdb`) or the in-memory sentinel `:memory:`
    * which tests use to get a fresh isolated database per call. */
  final case class Config(path: String, readOnly: Boolean = false)

  /** Open a connection in a [[Scope]]. On open we run the schema DDL
    * (idempotent CREATE TABLE IF NOT EXISTS) so a fresh file is
    * always usable.
    */
  def open(config: Config): RIO[Scope, Connection] =
    ZIO
      .acquireRelease(connect(config))(c =>
        ZIO.attempt(c.close()).ignoreLogged
      )
      .tap(initSchema)

  private def connect(config: Config): RIO[Any, Connection] =
    ZIO.attemptBlocking {
      val props = new Properties()
      if config.readOnly then props.setProperty("duckdb.read_only", "true")
      DriverManager.getConnection(s"jdbc:duckdb:${config.path}", props)
    }

  private def initSchema(conn: Connection): RIO[Any, Unit] =
    ZIO.attemptBlocking {
      val stmt = conn.createStatement()
      try
        // DuckDB's JDBC Statement.execute funnels through a prepared-
        // statement path internally. We're issuing DDL with no
        // placeholders, but the SQL still has to be one statement per
        // call — execute() rejects multi-statement strings. Looping
        // an explicit lambda also avoids any overload-resolution
        // ambiguity around the eta-expanded `stmt.execute` reference.
        Schema.statements.foreach { sql =>
          stmt.execute(sql.trim)
          ()
        }
      finally stmt.close()
    }

  /** Execute a parameterised UPDATE / INSERT / DDL statement. Returns
    * the affected-row count from JDBC. */
  def update(conn: Connection, sql: String, params: Seq[Any] = Nil): RIO[Any, Int] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(sql)
      try {
        bind(ps, params)
        ps.executeUpdate()
      } finally ps.close()
    }

  /** Execute a parameterised SELECT and project rows via `extract`. */
  def query[A](conn: Connection, sql: String, params: Seq[Any] = Nil)(
      extract: ResultSet => A
  ): RIO[Any, List[A]] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(sql)
      try {
        bind(ps, params)
        val rs = ps.executeQuery()
        try {
          val buf = scala.collection.mutable.ListBuffer.empty[A]
          while rs.next() do buf += extract(rs)
          buf.toList
        } finally rs.close()
      } finally ps.close()
    }

  /** Batch INSERT. JDBC batches amortise the per-row commit cost; for
    * the bulk-ingest path (PGN → millions of position_moves rows) this
    * is ~50× faster than per-row inserts. */
  def batchInsert(
      conn: Connection,
      sql: String,
      rows: Seq[Seq[Any]],
  ): RIO[Any, Unit] =
    if rows.isEmpty then ZIO.unit
    else
      ZIO.attemptBlocking {
        val ps = conn.prepareStatement(sql)
        try {
          rows.foreach { params =>
            bind(ps, params)
            ps.addBatch()
          }
          ps.executeBatch()
          ()
        } finally ps.close()
      }

  /** Bind a parameter sequence into a [[PreparedStatement]] in order.
    * Supports the JDBC-native types we actually use (Long, Int, Float,
    * String, Boolean) — anything else falls through to `setObject`
    * which usually does the right thing for DuckDB.
    */
  private def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { case (param, i) =>
      val idx = i + 1
      param match
        case v: Long    => ps.setLong(idx, v)
        case v: Int     => ps.setInt(idx, v)
        case v: Double  => ps.setDouble(idx, v)
        case v: Float   => ps.setFloat(idx, v)
        case v: String  => ps.setString(idx, v)
        case v: Boolean => ps.setBoolean(idx, v)
        case other      => ps.setObject(idx, other)
    }

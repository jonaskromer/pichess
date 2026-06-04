package chess.bot.data

/** DuckDB schema for the bot training corpus + opening book.
  *
  * One table per concern:
  *   - `games`              — source-game metadata (one row per PGN game)
  *   - `positions`          — every position seen, deduped by Zobrist
  *   - `position_moves`     — opening-book + move statistics
  *   - `training_positions` — Texel-tuner input (sampled "quiet" positions)
  *   - `eval_weights`       — versioned snapshots of Texel-tuned weights
  *
  * All DDL is `CREATE TABLE IF NOT EXISTS`, so opening a pre-existing
  * file is a no-op and a fresh file gets fully provisioned. Schema
  * evolution is deferred until we actually have a reason to bump it.
  */
object Schema:

  private val games: String = """
    CREATE TABLE IF NOT EXISTS games (
      id            UUID         PRIMARY KEY,
      white_elo     SMALLINT,
      black_elo     SMALLINT,
      result        VARCHAR(7),
      eco           VARCHAR(3),
      moves_san     VARCHAR,
      date          DATE
    )
  """

  private val positions: String = """
    CREATE TABLE IF NOT EXISTS positions (
      zobrist       BIGINT       PRIMARY KEY,
      fen           VARCHAR      NOT NULL,
      side_to_move  CHAR(1)      NOT NULL,
      material      SMALLINT
    )
  """

  private val positionMoves: String = """
    CREATE TABLE IF NOT EXISTS position_moves (
      zobrist       BIGINT       NOT NULL,
      move_uci      VARCHAR(5)   NOT NULL,
      wins          BIGINT       DEFAULT 0,
      draws         BIGINT       DEFAULT 0,
      losses        BIGINT       DEFAULT 0,
      sum_elo       BIGINT       DEFAULT 0,
      PRIMARY KEY (zobrist, move_uci)
    )
  """

  private val positionMovesIndex: String =
    "CREATE INDEX IF NOT EXISTS idx_position_moves_zobrist ON position_moves(zobrist)"

  private val trainingPositions: String = """
    CREATE TABLE IF NOT EXISTS training_positions (
      zobrist       BIGINT       NOT NULL,
      outcome       FLOAT        NOT NULL,
      quiet         BOOLEAN      NOT NULL,
      weight        FLOAT        NOT NULL DEFAULT 1.0,
      pawn_diff     SMALLINT     NOT NULL DEFAULT 0,
      knight_diff   SMALLINT     NOT NULL DEFAULT 0,
      bishop_diff   SMALLINT     NOT NULL DEFAULT 0,
      rook_diff     SMALLINT     NOT NULL DEFAULT 0,
      queen_diff    SMALLINT     NOT NULL DEFAULT 0
    )
  """

  private val trainingPositionsIndex: String =
    "CREATE INDEX IF NOT EXISTS idx_training_positions_zobrist ON training_positions(zobrist)"

  private val evalWeights: String = """
    CREATE TABLE IF NOT EXISTS eval_weights (
      version       INTEGER      NOT NULL,
      feature_name  VARCHAR      NOT NULL,
      value         INTEGER      NOT NULL,
      PRIMARY KEY (version, feature_name)
    )
  """

  /** The full DDL sequence run by [[Db.open]] on every connection.
    * Order matters where one CREATE depends on another (indexes after
    * the tables they index) — but DuckDB allows out-of-order DDL too,
    * we just keep this readable as a top-down schema definition.
    *
    * Declared AFTER its constituents so forward references resolve at
    * object-init time. With `val`s in source order, listing this
    * earlier produces nulls in the list (Scala object-init semantics).
    */
  val statements: List[String] = List(
    games,
    positions,
    positionMoves,
    positionMovesIndex,
    trainingPositions,
    trainingPositionsIndex,
    evalWeights,
  )

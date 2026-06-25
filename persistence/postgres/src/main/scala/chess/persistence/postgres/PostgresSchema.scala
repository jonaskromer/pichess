package chess.persistence.postgres

import slick.jdbc.PostgresProfile.api.*
import zio.*

/** Idempotent schema bootstrap. Runs once at service startup so neither the
  * gameService nor the repository needs to know about table DDL. Re-running
  * against an existing schema is a no-op.
  *
  * Production deployments would replace this with Flyway / Liquibase
  * migrations; this keeps Phase 1 self-contained without an extra tool.
  */
object PostgresSchema:

  /** Create both tables if they don't exist. */
  def ensure(db: PostgresDatabase): Task[Unit] =
    val createIfMissing = sqlu"""
      CREATE TABLE IF NOT EXISTS games (
        id          TEXT PRIMARY KEY,
        fen         TEXT NOT NULL,
        updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
      )
    """ >> sqlu"""
      CREATE TABLE IF NOT EXISTS lobbies (
        id                 TEXT PRIMARY KEY,
        invite_code        TEXT NOT NULL UNIQUE,
        host_nickname      TEXT NOT NULL,
        host_session_id    TEXT NOT NULL,
        guest_nickname     TEXT,
        guest_session_id   TEXT,
        visibility         TEXT NOT NULL,
        allow_undo         BOOLEAN NOT NULL,
        allow_spectate     BOOLEAN NOT NULL,
        spectator_limit    INTEGER NOT NULL,
        status             TEXT NOT NULL,
        game_id            TEXT,
        created_at         BIGINT NOT NULL,
        updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
      )
    """ >> sqlu"""
      CREATE INDEX IF NOT EXISTS lobbies_invite_code_idx
        ON lobbies (invite_code)
    """ >> sqlu"""
      CREATE INDEX IF NOT EXISTS lobbies_public_waiting_idx
        ON lobbies (visibility, status, created_at)
    """ >> sqlu"""
      CREATE TABLE IF NOT EXISTS game_archives (
        id    TEXT PRIMARY KEY,
        json  TEXT NOT NULL
      )
    """

    db.run(createIfMissing.transactionally).unit

  /** Used by tests to start from a clean slate. NOT exposed at runtime. */
  def reset(db: PostgresDatabase): Task[Unit] =
    val drop = sqlu"DROP TABLE IF EXISTS game_archives" >>
      sqlu"DROP TABLE IF EXISTS lobbies" >>
      sqlu"DROP TABLE IF EXISTS games"
    db.run(drop.transactionally).unit *> ensure(db)

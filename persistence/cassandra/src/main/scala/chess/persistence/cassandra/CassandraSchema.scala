package chess.persistence.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import zio.*

/** Idempotent CQL DDL for the games + lobbies tables, plus the keyspace
  * itself. Mirrors `PostgresSchema` — production deployments would replace
  * this with proper migrations (e.g. cassandra-migrate).
  *
  * The lobbies invite-code lookup uses a denormalised companion table
  * `lobbies_by_invite` rather than a Cassandra secondary index, since
  * secondary indexes don't scale across the cluster the way explicit
  * inverse tables do.
  */
object CassandraSchema:

  def ensure(session: CqlSession, keyspace: String): Task[Unit] =
    ZIO.attempt {
      val statements = List(
        s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}""",
        s"""CREATE TABLE IF NOT EXISTS $keyspace.games (
              game_id    text PRIMARY KEY,
              fen        text,
              updated_at timestamp
            )""",
        s"""CREATE TABLE IF NOT EXISTS $keyspace.lobbies (
              lobby_id          text PRIMARY KEY,
              invite_code       text,
              host_nickname     text,
              host_session_id   text,
              guest_nickname    text,
              guest_session_id  text,
              visibility        text,
              allow_undo        boolean,
              allow_spectate    boolean,
              spectator_limit   int,
              status            text,
              game_id           text,
              created_at        bigint,
              updated_at        timestamp
            )""",
        s"""CREATE TABLE IF NOT EXISTS $keyspace.lobbies_by_invite (
              invite_code text PRIMARY KEY,
              lobby_id    text
            )""",
        s"""CREATE TABLE IF NOT EXISTS $keyspace.game_archives (
              game_id text PRIMARY KEY,
              json    text
            )"""
      )
      // Wrap in SimpleStatement to dodge Scala 3's overload-resolution
      // wobble when calling the Java `Session.execute(String)` variant.
      statements.foreach(cql =>
        session.execute(SimpleStatement.newInstance(cql))
      )
    }

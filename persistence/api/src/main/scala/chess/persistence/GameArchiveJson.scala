package chess.persistence

import zio.json.*

import chess.model.{ArchivePly, GameArchive}

/** zio-json codecs for the archive, so every backend persists a [[GameArchive]]
  * as one JSON blob (uniform across Mongo/Postgres/Redis/Cassandra). Import
  * `GameArchiveJson.given` where the blob is read/written.
  */
object GameArchiveJson:
  given JsonCodec[ArchivePly]  = DeriveJsonCodec.gen[ArchivePly]
  given JsonCodec[GameArchive] = DeriveJsonCodec.gen[GameArchive]

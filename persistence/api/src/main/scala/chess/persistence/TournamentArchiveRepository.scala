package chess.persistence

import zio.*
import zio.json.*

import chess.model.{GameError, TournamentArchive, TournamentStanding}

/** Persists finished tournaments (ladder + game ids) for post-tournament
  * browsing. `save` is idempotent by `tournamentId` (last write wins), `find`
  * serves one tournament's detail, `list` powers the history index.
  *
  * Slice-2 scope (mongo + in-memory only); Redis/Postgres/Cassandra impls are a
  * follow-up, mirroring [[GameArchiveRepository]].
  */
trait TournamentArchiveRepository:
  def save(archive: TournamentArchive): IO[GameError, Unit]
  def find(tournamentId: String): IO[GameError, Option[TournamentArchive]]
  def list: IO[GameError, List[TournamentArchive]]

/** zio-json codecs so every backend stores a [[TournamentArchive]] as one JSON
  * blob. Import `TournamentArchiveJson.given` where the blob is read/written. */
object TournamentArchiveJson:
  given JsonCodec[TournamentStanding] = DeriveJsonCodec.gen[TournamentStanding]
  given JsonCodec[TournamentArchive]  = DeriveJsonCodec.gen[TournamentArchive]

/** In-memory tournament store (dev/test default). */
final class InMemoryTournamentArchiveRepository(
    store: Ref[Map[String, TournamentArchive]]
) extends TournamentArchiveRepository:

  def save(archive: TournamentArchive): IO[GameError, Unit] =
    store.update(_ + (archive.tournamentId -> archive))

  def find(tournamentId: String): IO[GameError, Option[TournamentArchive]] =
    store.get.map(_.get(tournamentId))

  def list: IO[GameError, List[TournamentArchive]] =
    store.get.map(_.values.toList)

object InMemoryTournamentArchiveRepository:
  val layer: ULayer[TournamentArchiveRepository] =
    ZLayer {
      Ref
        .make(Map.empty[String, TournamentArchive])
        .map(InMemoryTournamentArchiveRepository(_))
    }

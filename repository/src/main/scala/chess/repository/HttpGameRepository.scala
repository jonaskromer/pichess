package chess.repository

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.repository.api.{
  GameStateEnvelope,
  LoadFailure,
  RepositoryEndpoints
}
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.*

/** [[GameRepository]] impl that calls the REST service defined by
  * [[RepositoryEndpoints]] via a Tapir-generated typed Sttp client.
  *
  * Used when `repository` runs as its own Docker container; `game-service` gets
  * this layer in place of [[InMemoryGameRepository.layer]].
  *
  * All non-2xx responses and transport errors map to
  * [[GameError.InfrastructureError]]; the message preserves the server's
  * description where one is available.
  */
final class HttpGameRepository(
    baseUri: Uri,
    backend: SttpBackend[Task, Any]
) extends GameRepository:

  private val saveClient =
    SttpClientInterpreter()
      .toClientThrowDecodeFailures(
        RepositoryEndpoints.saveGame,
        Some(baseUri),
        backend
      )

  private val loadClient =
    SttpClientInterpreter()
      .toClientThrowDecodeFailures(
        RepositoryEndpoints.loadGame,
        Some(baseUri),
        backend
      )

  private val deleteClient =
    SttpClientInterpreter()
      .toClientThrowDecodeFailures(
        RepositoryEndpoints.deleteGame,
        Some(baseUri),
        backend
      )

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    saveClient((id, GameStateEnvelope(FenSerializer.serialize(state))))
      .mapError(transportError)
      .flatMap {
        case Right(_) => ZIO.unit
        case Left(errMsg) =>
          ZIO.fail(GameError.InfrastructureError(s"save rejected: $errMsg"))
      }

  def load(id: GameId): IO[GameError, Option[GameState]] =
    loadClient(id).mapError(transportError).flatMap {
      case Right(env) => FenParserRegex.parse(env.fen).map(Some(_))
      case Left(LoadFailure.NotFound) => ZIO.succeed(None)
      case Left(LoadFailure.ServerError(m)) =>
        ZIO.fail(GameError.InfrastructureError(s"load rejected: $m"))
    }

  def delete(id: GameId): IO[GameError, Unit] =
    deleteClient(id).mapError(transportError).flatMap {
      case Right(_) => ZIO.unit
      case Left(errMsg) =>
        ZIO.fail(GameError.InfrastructureError(s"delete rejected: $errMsg"))
    }

  private def transportError(t: Throwable): GameError =
    GameError.InfrastructureError(s"repository HTTP error: ${t.getMessage}")

object HttpGameRepository:

  /** Build a layer from a full base URI like `http://repository:8091`.
    *
    * `REPOSITORY_URL` env var is read if `baseUri` is blank — handy for Docker
    * Compose where the hostname is known only at runtime.
    */
  def layer(baseUri: String): ZLayer[Any, Throwable, GameRepository] =
    ZLayer.scoped {
      for
        backend <- HttpClientZioBackend.scoped()
        // Pre-validate via java.net.URI — sttp's Uri.parse is permissive
        // enough that bad inputs slip through and surface only at request
        // time. The JDK is strict (rejects unbalanced brackets, unencoded
        // spaces, …), giving a deterministic, testable failure mode.
        uri <- ZIO
          .attempt(java.net.URI.create(baseUri))
          .mapBoth(
            t => new RuntimeException(s"Invalid base uri: ${t.getMessage}"),
            parsed => Uri(parsed)
          )
      yield HttpGameRepository(uri, backend)
    }

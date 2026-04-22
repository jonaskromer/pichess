package chess.repository

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.repository.api.{GameStateEnvelope, RepositoryEndpoints}
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.*

/** [[GameRepository]] impl that calls the REST service defined by
  * [[RepositoryEndpoints]] via a Tapir-generated typed Sttp client.
  *
  * Used when `repository` runs as its own Docker container; `game-service`
  * gets this layer in place of [[InMemoryGameRepository.layer]].
  */
final class HttpGameRepository(
    baseUri: Uri,
    backend: SttpBackend[Task, Any],
) extends GameRepository:

  private val saveClient =
    SttpClientInterpreter()
      .toClientThrowDecodeFailures(
        RepositoryEndpoints.saveGame,
        Some(baseUri),
        backend,
      )

  private val loadClient =
    SttpClientInterpreter()
      .toClientThrowDecodeFailures(
        RepositoryEndpoints.loadGame,
        Some(baseUri),
        backend,
      )

  private val deleteClient =
    SttpClientInterpreter()
      .toClientThrowDecodeFailures(
        RepositoryEndpoints.deleteGame,
        Some(baseUri),
        backend,
      )

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    saveClient((id, GameStateEnvelope(FenSerializer.serialize(state))))
      .mapError(toGameError)
      .flatMap {
        case Right(_) => ZIO.unit
        case Left(_)  => ZIO.fail(GameError.ParseError("repository rejected save"))
      }

  def load(id: GameId): IO[GameError, Option[GameState]] =
    loadClient(id).mapError(toGameError).flatMap {
      case Right(env) => FenParserRegex.parse(env.fen).map(Some(_))
      case Left(_)    => ZIO.succeed(None) // 404 → None
    }

  def delete(id: GameId): IO[GameError, Unit] =
    deleteClient(id).mapError(toGameError).flatMap {
      case Right(_) => ZIO.unit
      case Left(_)  =>
        ZIO.fail(GameError.ParseError("repository rejected delete"))
    }

  private def toGameError(t: Throwable): GameError =
    GameError.ParseError(s"repository HTTP error: ${t.getMessage}")

object HttpGameRepository:

  /** Build a layer from a full base URI like `http://repository:8091`.
    *
    * `REPOSITORY_URL` env var is read if `baseUri` is blank — handy for
    * Docker Compose where the hostname is known only at runtime.
    */
  def layer(baseUri: String): ZLayer[Any, Throwable, GameRepository] =
    ZLayer.scoped {
      for
        backend <- HttpClientZioBackend.scoped()
        uri     <- ZIO
          .fromEither(Uri.parse(baseUri))
          .mapError(m => new RuntimeException(s"Invalid base uri: $m"))
      yield HttpGameRepository(uri, backend)
    }

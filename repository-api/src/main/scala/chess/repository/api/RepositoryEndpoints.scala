package chess.repository.api

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import zio.json.*

/** REST contract for the game repository service.
  *
  * `GameState` is serialized on the wire as FEN (Forsyth–Edwards Notation): a
  * short, canonical string that both sides convert with `FenSerializer` /
  * `FenParserRegex`. Keeping FEN as the wire format avoids ad-hoc JSON
  * encoders for the nested `Board` / `Piece` types and makes the service
  * curl-friendly for debugging.
  *
  *   PUT    /games/{id}  → save;   500 with message on any failure
  *   GET    /games/{id}  → load;   404 when absent, 500 with message on failure
  *   DELETE /games/{id}  → delete; idempotent, 500 with message on failure
  *
  * Save/delete pick 5xx (rather than 4xx) because the only consumer that
  * speaks this contract is [[HttpGameRepository]] and it can't structurally
  * produce malformed input — every reachable failure is server-side.
  */
final case class GameStateEnvelope(fen: String)

object GameStateEnvelope:
  given JsonEncoder[GameStateEnvelope] =
    DeriveJsonEncoder.gen[GameStateEnvelope]
  given JsonDecoder[GameStateEnvelope] =
    DeriveJsonDecoder.gen[GameStateEnvelope]

/** Typed error for [[RepositoryEndpoints.loadGame]] — distinguishes "no such
  * game" (which the client maps to `None`) from a real backend failure
  * (which the client treats as an infrastructure error).
  */
sealed trait LoadFailure

object LoadFailure:
  case object NotFound                            extends LoadFailure
  final case class ServerError(message: String)   extends LoadFailure

object RepositoryEndpoints:

  private val gamesBase = endpoint.in("games")

  private val loadErrorOut: EndpointOutput[LoadFailure] =
    oneOf[LoadFailure](
      oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(
        LoadFailure.NotFound
      ),
      oneOfVariant[LoadFailure.ServerError](
        StatusCode.InternalServerError,
        stringBody.map(LoadFailure.ServerError(_))(_.message),
      ),
    )

  val saveGame: PublicEndpoint[(String, GameStateEnvelope), String, Unit, Any] =
    gamesBase.put
      .in(path[String]("id"))
      .in(jsonBody[GameStateEnvelope])
      .out(statusCode(StatusCode.NoContent))
      .errorOut(statusCode(StatusCode.InternalServerError).and(stringBody))
      .name("saveGame")
      .description("Save (create or overwrite) a game's state by ID")

  val loadGame: PublicEndpoint[String, LoadFailure, GameStateEnvelope, Any] =
    gamesBase.get
      .in(path[String]("id"))
      .out(jsonBody[GameStateEnvelope])
      .errorOut(loadErrorOut)
      .name("loadGame")
      .description("Load a game's state by ID; 404 if unknown, 500 on failure")

  val deleteGame: PublicEndpoint[String, String, Unit, Any] =
    gamesBase.delete
      .in(path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(statusCode(StatusCode.InternalServerError).and(stringBody))
      .name("deleteGame")
      .description("Delete a game's state by ID; idempotent")

  val all: List[AnyEndpoint] = List(saveGame, loadGame, deleteGame)

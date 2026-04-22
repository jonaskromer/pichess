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
  *   PUT    /games/{id}  → save
  *   GET    /games/{id}  → load  (404 when absent)
  *   DELETE /games/{id}  → delete
  */
final case class GameStateEnvelope(fen: String)

object GameStateEnvelope:
  given JsonEncoder[GameStateEnvelope] =
    DeriveJsonEncoder.gen[GameStateEnvelope]
  given JsonDecoder[GameStateEnvelope] =
    DeriveJsonDecoder.gen[GameStateEnvelope]

object RepositoryEndpoints:

  private val gamesBase = endpoint.in("games")

  val saveGame: PublicEndpoint[(String, GameStateEnvelope), Unit, Unit, Any] =
    gamesBase.put
      .in(path[String]("id"))
      .in(jsonBody[GameStateEnvelope])
      .out(statusCode(StatusCode.NoContent))
      .name("saveGame")
      .description("Save (create or overwrite) a game's state by ID")

  val loadGame: PublicEndpoint[String, Unit, GameStateEnvelope, Any] =
    gamesBase.get
      .in(path[String]("id"))
      .out(jsonBody[GameStateEnvelope])
      .errorOut(statusCode(StatusCode.NotFound))
      .name("loadGame")
      .description("Load a game's state by ID; 404 if unknown")

  val deleteGame: PublicEndpoint[String, Unit, Unit, Any] =
    gamesBase.delete
      .in(path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .name("deleteGame")
      .description("Delete a game's state by ID; idempotent")

  val all: List[AnyEndpoint] = List(saveGame, loadGame, deleteGame)

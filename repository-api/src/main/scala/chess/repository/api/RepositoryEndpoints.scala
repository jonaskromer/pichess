package chess.repository.api

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*

/** REST contract for the game repository service.
  *
  * `GameState` is serialized on the wire as FEN (Forsyth–Edwards Notation): a
  * short, canonical string that both sides convert with `FenSerializer` /
  * `FenParserRegex`. Keeping FEN as the wire format avoids ad-hoc JSON encoders
  * for the nested `Board` / `Piece` types and makes the service curl-friendly
  * for debugging.
  *
  * PUT /games/{id} → save; 500 with message on any failure GET /games/{id} →
  * load; 404 when absent, 500 with message on failure DELETE /games/{id} →
  * delete; idempotent, 500 with message on failure
  *
  * Save/delete pick 5xx (rather than 4xx) because the only consumer that speaks
  * this contract is [[HttpGameRepository]] and it can't structurally produce
  * malformed input — every reachable failure is server-side.
  */
object RepositoryEndpoints:

  private val gamesBase = endpoint.in("games")

  private val loadErrorOut: EndpointOutput[LoadFailure] =
    oneOf[LoadFailure](
      oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(
        LoadFailure.NotFound
      ),
      oneOfVariant[LoadFailure.ServerError](
        StatusCode.InternalServerError,
        stringBody.map(RepositoryCodecs.serverErrorFromMessage)(
          RepositoryCodecs.serverErrorToMessage
        )
      )
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

  // -- Game archive (finished games persisted for analysis / replay) ---------

  private val archivesBase = endpoint.in("archives")

  // Explicit derivation so the nested `List[SubmittedMoveDto]` resolves via its
  // element schema rather than being mis-derived as a sum type.
  private given Schema[SubmittedMoveDto]     = Schema.derived
  private given Schema[ArchiveSubmissionDto] = Schema.derived
  private given Schema[ArchivePgnDto]        = Schema.derived

  val postArchive: PublicEndpoint[ArchiveSubmissionDto, String, Unit, Any] =
    archivesBase.post
      .in(jsonBody[ArchiveSubmissionDto])
      .out(statusCode(StatusCode.NoContent))
      .errorOut(statusCode(StatusCode.InternalServerError).and(stringBody))
      .name("postArchive")
      .description(
        "Persist a finished game (UCI moves + per-move clocks) as an analyzable archive"
      )

  val getArchive: PublicEndpoint[String, LoadFailure, ArchivePgnDto, Any] =
    archivesBase.get
      .in(path[String]("id"))
      .out(jsonBody[ArchivePgnDto])
      .errorOut(loadErrorOut)
      .name("getArchive")
      .description(
        "Fetch an archived game's PGN-with-clocks + metadata; 404 if unknown"
      )

  // -- tournament archives (post-tournament browse + ladder) ----------------
  private val tournamentsBase = endpoint.in("tournament-archives")

  private given Schema[TournamentStandingDto] = Schema.derived
  private given Schema[TournamentArchiveDto]   = Schema.derived
  private given Schema[TournamentSummaryDto]   = Schema.derived

  val postTournamentArchive
      : PublicEndpoint[TournamentArchiveDto, String, Unit, Any] =
    tournamentsBase.post
      .in(jsonBody[TournamentArchiveDto])
      .out(statusCode(StatusCode.NoContent))
      .errorOut(statusCode(StatusCode.InternalServerError).and(stringBody))
      .name("postTournamentArchive")
      .description("Persist a finished tournament's ladder + game ids")

  val listTournamentArchives
      : PublicEndpoint[Unit, String, List[TournamentSummaryDto], Any] =
    tournamentsBase.get
      .out(jsonBody[List[TournamentSummaryDto]])
      .errorOut(statusCode(StatusCode.InternalServerError).and(stringBody))
      .name("listTournamentArchives")
      .description("List archived tournaments (history index)")

  val getTournamentArchive
      : PublicEndpoint[String, LoadFailure, TournamentArchiveDto, Any] =
    tournamentsBase.get
      .in(path[String]("id"))
      .out(jsonBody[TournamentArchiveDto])
      .errorOut(loadErrorOut)
      .name("getTournamentArchive")
      .description(
        "Fetch one archived tournament's ladder + game ids; 404 if unknown"
      )

  val all: List[AnyEndpoint] =
    List(
      saveGame,
      loadGame,
      deleteGame,
      postArchive,
      getArchive,
      postTournamentArchive,
      listTournamentArchives,
      getTournamentArchive
    )

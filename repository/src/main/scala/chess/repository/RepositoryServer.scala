package chess.repository

import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*

import chess.codec.{FenParserRegex, FenSerializer}
import chess.opening.EcoBook
import chess.persistence.{GameArchiveRepository, GameRepository}
import chess.repository.api.{
  ArchivePgnDto,
  GameStateEnvelope,
  LoadFailure,
  RepositoryEndpoints
}

/** Tapir-backed HTTP server that exposes [[GameRepository]] over REST.
  *
  * Wire format for `GameState` is FEN — short, canonical, already parsed by
  * [[FenParserRegex]] and produced by [[FenSerializer]]. This keeps the API
  * contract (see [[RepositoryEndpoints]]) free of nested JSON schemas for Board
  * / Piece / etc.
  *
  * Failure modes surface as HTTP errors rather than dying: any save/delete
  * failure is a 500 with a descriptive message; load returns 404 for missing
  * games and 500 for backend errors.
  */
object RepositoryServer:

  def routes(
      repo: GameRepository,
      archiveRepo: GameArchiveRepository,
      eco: EcoBook
  ): Routes[Any, Response] =
    ZioHttpInterpreter().toHttp(
      List(
        RepositoryEndpoints.postArchive.zServerLogic[Any] { dto =>
          TournamentArchive
            .fromSubmission(dto, eco)
            .mapError(err => s"Archive build failed: ${err.message}")
            .flatMap(archive =>
              archiveRepo
                .save(archive)
                .mapError(err => s"Archive save failed: ${err.message}")
            )
            .unit
        },
        RepositoryEndpoints.getArchive.zServerLogic[Any] { id =>
          archiveRepo
            .find(id)
            .foldZIO(
              err =>
                ZIO.fail(
                  LoadFailure.ServerError(s"Archive load failed: ${err.message}")
                ),
              {
                case Some(a) =>
                  ZIO.succeed(
                    ArchivePgnDto(a.pgn, a.white, a.black, a.result, a.openingName)
                  )
                case None => ZIO.fail(LoadFailure.NotFound)
              }
            )
        },
        RepositoryEndpoints.saveGame.zServerLogic[Any] { case (id, env) =>
          FenParserRegex
            .parse(env.fen)
            .mapError(err => s"Invalid FEN: ${err.message}")
            .flatMap(state =>
              repo
                .save(id, state)
                .mapError(err => s"Save failed: ${err.message}")
            )
            .unit
        },
        RepositoryEndpoints.loadGame.zServerLogic[Any] { id =>
          repo
            .load(id)
            .foldZIO(
              err =>
                ZIO.fail(
                  LoadFailure.ServerError(s"Load failed: ${err.message}")
                ),
              {
                case Some(state) =>
                  ZIO.succeed(GameStateEnvelope(FenSerializer.serialize(state)))
                case None => ZIO.fail(LoadFailure.NotFound)
              }
            )
        },
        RepositoryEndpoints.deleteGame.zServerLogic[Any] { id =>
          repo.delete(id).mapError(err => s"Delete failed: ${err.message}").unit
        }
      )
    )

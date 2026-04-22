package chess.repository

import chess.codec.{FenParserRegex, FenSerializer}
import chess.repository.api.{GameStateEnvelope, RepositoryEndpoints}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*

/** Tapir-backed HTTP server that exposes [[GameRepository]] over REST.
  *
  * Wire format for `GameState` is FEN — short, canonical, already parsed by
  * [[FenParserRegex]] and produced by [[FenSerializer]]. This keeps the API
  * contract (see [[RepositoryEndpoints]]) free of nested JSON schemas for
  * Board / Piece / etc. Malformed FEN on save is treated as a server-side
  * defect (5xx) because well-behaved clients use [[FenSerializer]].
  */
object RepositoryServer:

  def routes(repo: GameRepository): Routes[Any, Response] =
    ZioHttpInterpreter().toHttp(
      List(
        RepositoryEndpoints.saveGame.zServerLogic[Any] { case (id, env) =>
          for
            state <- FenParserRegex.parse(env.fen).orDie
            _     <- repo.save(id, state).orDie
          yield ()
        },
        RepositoryEndpoints.loadGame.zServerLogic[Any] { id =>
          repo.load(id).orDie.flatMap {
            case None        => ZIO.fail(())
            case Some(state) =>
              ZIO.succeed(GameStateEnvelope(FenSerializer.serialize(state)))
          }
        },
        RepositoryEndpoints.deleteGame.zServerLogic[Any] { id =>
          repo.delete(id).orDie.unit
        },
      )
    )

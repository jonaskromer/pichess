package chess.controller

import chess.model.GameError
import chess.model.board.{GameState, Move}
import chess.notation.{
  CoordinateResolver,
  CastlingResolver,
  NotationResolver,
  SanResolver
}
import zio.*

object MoveParser:

  private val resolvers: List[NotationResolver] =
    List(CoordinateResolver, CastlingResolver, SanResolver)

  def parse(input: String, state: GameState): IO[GameError, Move] =
    val trimmed = input.trim
    ZIO
      .foldLeft(resolvers)(Option.empty[Move]) { (acc, resolver) =>
        acc match
          case some @ Some(_) => ZIO.succeed(some)
          case None           => resolver.parse(trimmed, state)
      }
      .flatMap {
        case Some(move) => ZIO.succeed(move)
        case None =>
          ZIO.fail(
            GameError.ParseError(
              "Invalid move. Type 'help' for notation guide"
            )
          )
      }

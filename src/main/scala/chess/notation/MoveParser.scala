package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move}
import zio.*

/** Unified entry point for converting a user-supplied move string into a
  * [[Move]] given the current [[GameState]]. Tries each registered
  * [[NotationResolver]] in order (coordinate → castling → SAN) and returns
  * the first successful parse.
  *
  * Lives in `chess.notation` rather than `chess.controller` because it is a
  * notation concern — composing the resolvers already defined here — and is
  * used by both the controller layer (TUI/web input) and the codec layer
  * (PGN replay). Having it in `controller` would invert the layering since
  * `codec.PgnParser` would then depend on `controller`.
  */
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

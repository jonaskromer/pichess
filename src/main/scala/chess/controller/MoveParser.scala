package chess.controller

import chess.model.GameError
import chess.model.board.{GameState, Move}
import chess.notation.{
  CoordinateResolver,
  CastlingResolver,
  NotationResolver,
  SanResolver
}

object MoveParser:

  // Resolvers are composed monadically via Option's orElse:
  // each resolver returns Option[Either[GameError, Move]].
  // orElse chains them: try the first; if None, try the next.
  // The first Some(...) wins — either as a successful parse or a matched error.

  private val resolver: (String, GameState) => Option[Either[GameError, Move]] =
    List[NotationResolver](CoordinateResolver, CastlingResolver, SanResolver)
      .map(r => r.parse(_, _))
      .reduceLeft: (combined, next) =>
        (input, state) => combined(input, state).orElse(next(input, state))

  def parse(input: String, state: GameState): Either[GameError, Move] =
    resolver(input.trim, state)
      .getOrElse(
        Left(
          GameError.ParseError("Invalid move. Type 'help' for notation guide")
        )
      )

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
  private val resolvers: List[NotationResolver] = List(
    CoordinateResolver,
    CastlingResolver,
    SanResolver
  )

  def parse(input: String, state: GameState): Either[GameError, Move] =
    val trimmed = input.trim
    resolvers.iterator
      .flatMap(_.parse(trimmed, state))
      .nextOption()
      .getOrElse(
        Left(
          GameError.ParseError("Invalid move. Type 'help' for notation guide")
        )
      )

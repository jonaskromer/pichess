package chess.model.rules

import chess.codec.{FenParserRegex, FenSerializer}
import chess.notation.MoveParser
import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

/** Explicit combinatorial sequences that exercise position-identity fields
  * '''in combination''', not just in isolation.
  *
  * Existing unit tests thoroughly cover castling rights, en passant, and
  * promotion as single-move mechanics. What they don't cover is the
  * '''co-variation''' of these fields in the same game: does the state remain
  * internally consistent when ep target, castling rights, and board placement
  * all evolve together?
  *
  * Each test here sets up or walks to a state that combines two or more of
  * these fields and asserts every one of them explicitly.
  */
object StateCombinationsSpec extends ZIOSpecDefault:

  /** Replay a list of SAN moves starting from `initial`, returning the final
    * state. Fails on the first invalid move so assertion failures point at the
    * offending step.
    */
  private def replay(
      initial: GameState,
      sanMoves: List[String]
  ): IO[GameError, GameState] =
    ZIO.foldLeft(sanMoves)(initial) { (state, san) =>
      for
        move <- MoveParser.parse(san, state)
        next <- Game.applyMove(state, move)
      yield next
    }

  def spec = suite("State combinations")(
    test(
      "ep target set and partial castling rights co-exist in a single position"
    ) {
      // 1.Nf3 Nf6 2.Rg1 (h1→g1, revokes whiteKingSide) 2...d5 (double push, sets ep=d6)
      // Final: castling = (T, T, T, F)... wait no. Rg1 revokes whiteKingSide only.
      // So castling should be (whiteKing=F, whiteQueen=T, blackKing=T, blackQueen=T)
      // and ep target = d6.
      val moves = List("Nf3", "Nf6", "Rg1", "d5")
      for finalState <- replay(GameState.initial, moves)
      yield assertTrue(
        !finalState.castlingRights.whiteKingSide, // lost via Rg1
        finalState.castlingRights.whiteQueenSide,
        finalState.castlingRights.blackKingSide,
        finalState.castlingRights.blackQueenSide,
        finalState.enPassantTarget.contains(Position('d', 6)),
        finalState.activeColor == Color.White,
        finalState.board(Position('g', 1)) ==
          Piece(Color.White, PieceType.Rook),
        finalState.board(Position('d', 5)) ==
          Piece(Color.Black, PieceType.Pawn)
      )
    },
    test(
      "asymmetric castling rights evolve correctly across multiple moves"
    ) {
      // Goal: reach final (F, F, T, F) — W lost both, B lost only queenside.
      // 1.Nf3 a6 2.e4 Ra7 (black rook a8→a7, loses blackQueenSide)
      // 3.Ke2 (white king moves, loses whiteKing + whiteQueen)
      val moves = List("Nf3", "a6", "e4", "Ra7", "Ke2")
      for finalState <- replay(GameState.initial, moves)
      yield assertTrue(
        !finalState.castlingRights.whiteKingSide,
        !finalState.castlingRights.whiteQueenSide,
        finalState.castlingRights.blackKingSide,
        !finalState.castlingRights.blackQueenSide,
        finalState.activeColor == Color.Black,
        finalState.board(Position('e', 2)) ==
          Piece(Color.White, PieceType.King),
        finalState.board(Position('a', 7)) ==
          Piece(Color.Black, PieceType.Rook),
        finalState.enPassantTarget.isEmpty
      )
    },
    test(
      "rook captured on starting square revokes castling while ep was active"
    ) {
      // Start from a custom FEN where:
      //  - white has a pawn on d4 (just double-pushed → ep target d3)
      //  - white has only Q (queenside) castling right (the relevant rook on a1)
      //  - black has a rook on a2 ready to capture white's a1 rook
      //
      // This checks: capturing the rook on a1 correctly revokes whiteQueenSide
      // even though ep target was set on that move's predecessor.
      val startFen = "4k3/8/8/8/3P4/8/r7/R3K3 b Q d3 0 1"
      for
        start <- FenParserRegex.parse(startFen)
        // Sanity: starting state has the combination we're testing
        _ <- ZIO.unless(
          start.castlingRights.whiteQueenSide &&
            start.enPassantTarget.contains(Position('d', 3))
        )(
          ZIO.fail(
            GameError.ParseError("starting FEN does not set up the combination")
          )
        )
        after <- replay(start, List("Rxa1"))
      yield assertTrue(
        // All castling rights gone (only Q was set; captured rook revoked it)
        !after.castlingRights.whiteKingSide,
        !after.castlingRights.whiteQueenSide,
        !after.castlingRights.blackKingSide,
        !after.castlingRights.blackQueenSide,
        // Capturing rook now on a1
        after.board(Position('a', 1)) ==
          Piece(Color.Black, PieceType.Rook),
        after.board.get(Position('a', 2)).isEmpty,
        // ep target cleared by any non-double-push move
        after.enPassantTarget.isEmpty,
        after.activeColor == Color.White
      )
    },
    test(
      "promotion followed by castling-right-affecting move preserves both changes"
    ) {
      // Start: white pawn on e7 about to promote, delivering check to bK on e3.
      // Black rook on h8 is incidental target; white has only K castling.
      //
      // 1. e8=Q+ (promote, gives check along e-file)
      // 1...Kf3 (black king moves out of check)
      // 2. Kf1 (white king moves, revokes whiteKingSide)
      //
      // Final state: promoted queen on e8, no castling rights remain.
      val startFen = "7r/4P3/8/8/8/4k3/8/4K2R w K - 0 1"
      val moves = List("e8=Q", "Kf3", "Kf1")
      for
        start <- FenParserRegex.parse(startFen)
        after <- replay(start, moves)
      yield assertTrue(
        // Promoted queen in place
        after.board(Position('e', 8)) ==
          Piece(Color.White, PieceType.Queen),
        after.board.get(Position('e', 7)).isEmpty,
        // Black king reached f3
        after.board(Position('f', 3)) ==
          Piece(Color.Black, PieceType.King),
        // White king on f1, all castling lost via king move
        after.board(Position('f', 1)) ==
          Piece(Color.White, PieceType.King),
        !after.castlingRights.whiteKingSide,
        !after.castlingRights.whiteQueenSide,
        !after.castlingRights.blackKingSide,
        !after.castlingRights.blackQueenSide,
        after.activeColor == Color.Black,
        after.enPassantTarget.isEmpty
      )
    },
    test(
      "FEN round-trip preserves ep + partial castling + all state fields"
    ) {
      // Positive test: any position with an ep target AND partial castling
      // should survive FEN round-trip unchanged. Regression guard for the
      // exact combination that the spec above constructs dynamically.
      val moves = List("Nf3", "Nf6", "Rg1", "d5")
      for
        state <- replay(GameState.initial, moves)
        fen = FenSerializer.serialize(state)
        reparsed <- FenParserRegex.parse(fen)
      yield assertTrue(
        reparsed.board == state.board,
        reparsed.activeColor == state.activeColor,
        reparsed.enPassantTarget == state.enPassantTarget,
        reparsed.castlingRights == state.castlingRights,
        reparsed.halfmoveClock == state.halfmoveClock,
        reparsed.fullmoveNumber == state.fullmoveNumber,
        reparsed.inCheck == state.inCheck
      )
    }
  )

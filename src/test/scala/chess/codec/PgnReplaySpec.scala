package chess.codec

import chess.model.board.{CastlingRights, GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

/** Realistic-length PGN replay tests.
  *
  * The existing [[PgnParserSpec]] focuses on parser edge cases (comments, NAGs,
  * malformed input) using short 1–4 move PGNs. This spec complements it by
  * replaying multi-move games that exercise '''game-state transitions''' not
  * reached by the short unit games:
  *
  *   - both colors castling in the same replay
  *   - en passant captures mid-replay
  *   - pawn promotion mid-replay
  *
  * After replay, every relevant state field is asserted explicitly so that any
  * divergence (e.g. a castling right that fails to revoke, an ep target that
  * lingers, a captured pawn that isn't removed) is caught at the assertion
  * level — not hidden behind an aggregate check.
  */
object PgnReplaySpec extends ZIOSpecDefault:

  def spec = suite("PGN realistic-length replay")(
    test("Ruy Lopez opening: both colors castle kingside in same game") {
      val pgn =
        """[Event "Ruy Lopez Main Line"]
          |[Result "*"]
          |
          |1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 6. Re1 b5
          |7. Bb3 d6 8. c3 O-O 9. h3 Na5 10. Bc2 c5 *""".stripMargin
      for result <- PgnParser.parse(pgn)
      yield
        val finalState = result.state
        assertTrue(
          result.moves.length == 20,
          // Both castled — all castling rights revoked
          finalState.castlingRights ==
            CastlingRights(false, false, false, false),
          // 10...c5 is a double push → ep target on c6, white to move
          finalState.enPassantTarget.contains(Position('c', 6)),
          finalState.activeColor == Color.White,
          finalState.fullmoveNumber == 11,
          // Both kings in their castled locations
          finalState.board(Position('g', 1)) ==
            Piece(Color.White, PieceType.King),
          finalState.board(Position('g', 8)) ==
            Piece(Color.Black, PieceType.King),
          // Mid-replay assertion: after 5.O-O (history index 8, white's 5th),
          // white king on g1, rook on f1, white castling rights revoked
          result.history(8)._2.board(Position('g', 1)) ==
            Piece(Color.White, PieceType.King),
          result.history(8)._2.board(Position('f', 1)) ==
            Piece(Color.White, PieceType.Rook),
          !result.history(8)._2.castlingRights.whiteKingSide,
          !result.history(8)._2.castlingRights.whiteQueenSide,
          // Black still has both rights at that point
          result.history(8)._2.castlingRights.blackKingSide,
          result.history(8)._2.castlingRights.blackQueenSide
        )
    },
    test("en passant capture mid-game removes the captured pawn") {
      val pgn =
        """[Event "En Passant Test"]
          |[Result "*"]
          |
          |1. e4 Nf6 2. e5 d5 3. exd6 cxd6 4. d4 e5 5. dxe5 dxe5 *""".stripMargin
      for result <- PgnParser.parse(pgn)
      yield
        val finalState = result.state
        // After 3.exd6 (history index 4): white pawn on d6, the captured black
        // pawn at d5 has been removed.
        val afterExd6 = result.history(4)._2
        assertTrue(
          result.moves.length == 10,
          // The ep capture: white pawn arrived on d6, black pawn on d5 gone
          afterExd6.board(Position('d', 6)) ==
            Piece(Color.White, PieceType.Pawn),
          afterExd6.board.get(Position('d', 5)).isEmpty,
          afterExd6.board.get(Position('e', 5)).isEmpty,
          afterExd6.halfmoveClock == 0, // capture resets halfmove
          afterExd6.enPassantTarget.isEmpty,
          // Final state: black pawn on e5 after mutual captures
          finalState.board(Position('e', 5)) ==
            Piece(Color.Black, PieceType.Pawn),
          finalState.board.get(Position('d', 4)).isEmpty,
          finalState.board.get(Position('d', 6)).isEmpty,
          finalState.activeColor == Color.White,
          finalState.enPassantTarget.isEmpty
        )
    },
    test("promotion with check from FEN start position") {
      val pgn =
        """[FEN "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"]
          |[Result "*"]
          |
          |1. a8=Q+ Kf7 2. Qb7+ Kg8 *""".stripMargin
      for result <- PgnParser.parse(pgn)
      yield
        val finalState = result.state
        // After 1.a8=Q (history index 0)
        val afterPromotion = result.history(0)._2
        assertTrue(
          result.moves.length == 4,
          // Promoted queen appears on a8
          afterPromotion.board(Position('a', 8)) ==
            Piece(Color.White, PieceType.Queen),
          afterPromotion.board.get(Position('a', 7)).isEmpty,
          afterPromotion.inCheck, // check delivered along rank 8
          afterPromotion.halfmoveClock == 0, // pawn move resets halfmove
          // Final state after 2...Kg8
          finalState.board(Position('b', 7)) ==
            Piece(Color.White, PieceType.Queen),
          finalState.board(Position('g', 8)) ==
            Piece(Color.Black, PieceType.King),
          finalState.board.get(Position('a', 8)).isEmpty,
          finalState.activeColor == Color.White,
          finalState.enPassantTarget.isEmpty
        )
    }
  )

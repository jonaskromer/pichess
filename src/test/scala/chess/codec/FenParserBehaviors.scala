package chess.codec

import chess.model.board.{CastlingRights, GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

/** Shared test cases for every [[FenParser]] implementation.
  *
  * The three parsers (combinator, fastparse, regex) must agree on the semantic
  * result for every well-formed and malformed FEN string. This object expresses
  * that agreement as a parameterized [[Spec]] that each parser-specific spec
  * mixes in.
  */
object FenParserBehaviors:

  val initialFen: String =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  val afterE4Fen: String =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  val noCastlingFen: String =
    "4k3/8/8/8/8/8/8/4K3 w - - 0 1"

  val partialCastlingFen: String =
    "r3k2r/8/8/8/8/8/8/R3K2R b Kq - 0 1"

  /** Black to move, black king on e8 attacked by white rook on e7. */
  val blackInCheckFen: String =
    "4k3/4R3/8/8/8/8/8/4K3 b - - 0 1"

  def behaviors(parser: FenParser): Spec[Any, Any] =
    suite("FenParser shared behaviors")(
      test("parses the standard initial position") {
        for state <- parser.parse(initialFen)
        yield assertTrue(state == GameState.initial)
      },
      test("parses a position with an en passant target square") {
        for state <- parser.parse(afterE4Fen)
        yield assertTrue(
          state.activeColor == Color.Black,
          state.enPassantTarget == Some(Position('e', 3)),
          state.board(Position('e', 4)) == Piece(Color.White, PieceType.Pawn),
          !state.board.contains(Position('e', 2))
        )
      },
      test("parses a position with all castling rights cleared") {
        for state <- parser.parse(noCastlingFen)
        yield
          val cr = state.castlingRights
          assertTrue(
            !cr.whiteKingSide,
            !cr.whiteQueenSide,
            !cr.blackKingSide,
            !cr.blackQueenSide
          )
      },
      test("parses a position with partial castling rights") {
        for state <- parser.parse(partialCastlingFen)
        yield
          val cr = state.castlingRights
          assertTrue(
            cr.whiteKingSide,
            !cr.whiteQueenSide,
            !cr.blackKingSide,
            cr.blackQueenSide,
            state.activeColor == Color.Black
          )
      },
      test("computes inCheck for the active color") {
        for state <- parser.parse(blackInCheckFen)
        yield assertTrue(state.inCheck)
      },
      test("does not flag inCheck when the active king is safe") {
        for state <- parser.parse(initialFen)
        yield assertTrue(!state.inCheck)
      },
      test("places every piece in the right square for the initial board") {
        for state <- parser.parse(initialFen)
        yield assertTrue(
          state.board(Position('a', 1)) == Piece(Color.White, PieceType.Rook),
          state.board(Position('e', 1)) == Piece(Color.White, PieceType.King),
          state.board(Position('d', 8)) == Piece(Color.Black, PieceType.Queen),
          state.board(Position('h', 8)) == Piece(Color.Black, PieceType.Rook),
          state.board(Position('a', 2)) == Piece(Color.White, PieceType.Pawn),
          state.board(Position('h', 7)) == Piece(Color.Black, PieceType.Pawn),
          state.board.size == 32
        )
      },
      test("round-trips the initial position via FenSerializer") {
        val fen = FenSerializer.serialize(GameState.initial)
        for state <- parser.parse(fen)
        yield assertTrue(state == GameState.initial)
      },
      test("round-trips a position with custom castling rights") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(
            whiteKingSide = true,
            whiteQueenSide = false,
            blackKingSide = false,
            blackQueenSide = true
          )
        )
        for round <- parser.parse(FenSerializer.serialize(state))
        yield assertTrue(
          round.castlingRights == state.castlingRights,
          round.board == state.board,
          round.activeColor == state.activeColor
        )
      },
      test("round-trips halfmove clock and fullmove number") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.Black,
          castlingRights = CastlingRights(false, false, false, false),
          halfmoveClock = 7,
          fullmoveNumber = 23
        )
        for round <- parser.parse(FenSerializer.serialize(state))
        yield assertTrue(round.halfmoveClock == 7, round.fullmoveNumber == 23)
      },
      test("rejects an empty input") {
        for exit <- parser.parse("").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an input with too few fields") {
        for exit <- parser
            .parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w")
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an input with trailing garbage") {
        for exit <- parser.parse(initialFen + " extra").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an input with fewer than 8 ranks") {
        for exit <- parser
            .parse("rnbqkbnr/pppppppp/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an input with more than 8 ranks") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a rank that does not sum to 8") {
        for exit <- parser
            .parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPP/RNBQKBNR w KQkq - 0 1")
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a rank that overflows 8 squares") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPPP/RNBQKBNR w KQkq - 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an input with an invalid piece character") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPxPP/RNBQKBNR w KQkq - 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an input with an invalid active color") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects duplicate castling characters") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KKkq - 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an invalid castling character") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KZkq - 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects an invalid en passant target") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq z9 0 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a non-numeric halfmove clock") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - x 1"
            )
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects a zero fullmove number") {
        for exit <- parser
            .parse(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 0"
            )
            .exit
        yield assertTrue(exit.isFailure)
      }
    )

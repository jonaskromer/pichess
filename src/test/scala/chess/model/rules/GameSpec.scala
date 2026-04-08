package chess.model.rules

import chess.model.GameError
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{CastlingRights, GameState, GameStatus, Move, Position}
import zio.*
import zio.test.*

object GameSpec extends ZIOSpecDefault:

  private val initial = GameState.initial

  private lazy val enPassantState = GameState(
    Map(
      Position('e', 5) -> Piece(Color.White, PieceType.Pawn),
      Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
    ),
    Color.White,
    enPassantTarget = Some(Position('d', 6))
  )

  def spec = suite("Game.applyMove")(
    test("move a piece from source to destination") {
      for result <- Game
          .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
      yield assertTrue(
        result.board(Position('e', 4)) == Piece(Color.White, PieceType.Pawn)
      )
    },
    test("empty the source square after a move") {
      for result <- Game
          .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
      yield assertTrue(result.board.get(Position('e', 2)).isEmpty)
    },
    test("switch active color from White to Black") {
      for result <- Game
          .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
      yield assertTrue(result.activeColor == Color.Black)
    },
    test("switch active color from Black to White") {
      for
        afterWhite <- Game
          .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        result <- Game
          .applyMove(afterWhite, Move(Position('e', 7), Position('e', 5)))
      yield assertTrue(result.activeColor == Color.White)
    },
    test("capture an opponent piece by overwriting the destination") {
      val s = GameState(
        Map(
          Position('d', 1) -> Piece(Color.White, PieceType.Queen),
          Position('g', 4) -> Piece(Color.Black, PieceType.Pawn)
        ),
        Color.White
      )
      for result <- Game.applyMove(s, Move(Position('d', 1), Position('g', 4)))
      yield assertTrue(
        result.board(Position('g', 4)) == Piece(Color.White, PieceType.Queen)
      )
    },
    test("fail when the source square is empty") {
      for err <- Game
          .applyMove(initial, Move(Position('e', 4), Position('e', 5)))
          .flip
      yield assertTrue(err.message.contains("e4"))
    },
    test("fail when moving an opponent's piece") {
      for err <- Game
          .applyMove(initial, Move(Position('e', 7), Position('e', 5)))
          .flip
      yield assertTrue(err.message.contains("Black"))
    },
    // ─── En passant target tracking ─────────────────────────────────────────────
    suite("en passant target")(
      test("set after white pawn double advance") {
        for result <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(result.enPassantTarget == Some(Position('e', 3)))
      },
      test("set after black pawn double advance") {
        for
          afterWhite <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game
            .applyMove(afterWhite, Move(Position('d', 7), Position('d', 5)))
        yield assertTrue(result.enPassantTarget == Some(Position('d', 6)))
      },
      test("clear after a non-double-advance move") {
        for
          withTarget <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game
            .applyMove(withTarget, Move(Position('d', 7), Position('d', 6)))
        yield assertTrue(result.enPassantTarget.isEmpty)
      },
      test("overwrite when a second double advance follows") {
        for
          afterWhite <- Game.applyMove(
            initial,
            Move(Position('e', 2), Position('e', 4))
          )
          afterBlack <- Game
            .applyMove(afterWhite, Move(Position('d', 7), Position('d', 5)))
        yield assertTrue(
          afterWhite.enPassantTarget == Some(Position('e', 3)),
          afterBlack.enPassantTarget == Some(Position('d', 6))
        )
      },
      test("clear after a single pawn advance") {
        for
          withTarget <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game
            .applyMove(withTarget, Move(Position('a', 7), Position('a', 6)))
        yield assertTrue(result.enPassantTarget.isEmpty)
      }
    ),
    // ─── En passant capture mechanics ───────────────────────────────────────────
    suite("en passant capture")(
      test("place pawn on target square") {
        for result <- Game
            .applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(
          result.board.get(Position('d', 6)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("remove captured pawn") {
        for result <- Game
            .applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(result.board.get(Position('d', 5)).isEmpty)
      },
      test("clear en passant target") {
        for result <- Game
            .applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(result.enPassantTarget.isEmpty)
      }
    ),
    // ─── Pawn promotion ───────────────────────────────────────────────────────
    suite("pawn promotion")(
      test("promote white pawn to queen on rank 8") {
        val s = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for result <- Game.applyMove(
            s,
            Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
          )
        yield assertTrue(
          result.board(Position('e', 8)) == Piece(Color.White, PieceType.Queen)
        )
      },
      test("promote white pawn to knight (underpromotion)") {
        val s = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for result <- Game.applyMove(
            s,
            Move(Position('e', 7), Position('e', 8), Some(PieceType.Knight))
          )
        yield assertTrue(
          result.board(Position('e', 8)) == Piece(Color.White, PieceType.Knight)
        )
      },
      test("promote black pawn to queen on rank 1") {
        val s = GameState(
          Map(Position('d', 2) -> Piece(Color.Black, PieceType.Pawn)),
          Color.Black
        )
        for result <- Game.applyMove(
            s,
            Move(Position('d', 2), Position('d', 1), Some(PieceType.Queen))
          )
        yield assertTrue(
          result.board(Position('d', 1)) == Piece(Color.Black, PieceType.Queen)
        )
      },
      test("reject pawn reaching back rank without promotion") {
        val s = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for err <- Game
            .applyMove(s, Move(Position('e', 7), Position('e', 8)))
            .flip
        yield assertTrue(err.message.contains("must promote"))
      },
      test("reject promotion on a non-back-rank move") {
        val s = GameState(
          Map(Position('e', 2) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for err <- Game
            .applyMove(
              s,
              Move(Position('e', 2), Position('e', 3), Some(PieceType.Queen))
            )
            .flip
        yield assertTrue(err.message.contains("back rank"))
      },
      test("reject promotion to King") {
        val s = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for err <- Game
            .applyMove(
              s,
              Move(Position('e', 7), Position('e', 8), Some(PieceType.King))
            )
            .flip
        yield assertTrue(err.message.contains("Queen, Rook, Bishop, or Knight"))
      },
      test("remove pawn from source after promotion") {
        val s = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for result <- Game.applyMove(
            s,
            Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
          )
        yield assertTrue(result.board.get(Position('e', 7)).isEmpty)
      },
      test("promote via capture") {
        val s = GameState(
          Map(
            Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game.applyMove(
            s,
            Move(Position('e', 7), Position('d', 8), Some(PieceType.Queen))
          )
        yield assertTrue(
          result.board(Position('d', 8)) == Piece(Color.White, PieceType.Queen)
        )
      }
    ),
    // ─── Check rules ──────────────────────────────────────────────────────────
    suite("check")(
      test("reject move that leaves own king in check") {
        // White king on e1, white rook on e2 shielding from black rook on e8
        // Moving the rook off the e-file exposes the king
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 2) -> Piece(Color.White, PieceType.Rook),
            Position('e', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for err <- Game
            .applyMove(s, Move(Position('e', 2), Position('d', 2)))
            .flip
        yield assertTrue(err.message.contains("check"))
      },
      test("allow move that blocks check") {
        // White king on e1, black rook on e8 giving check, white rook on a2 blocks
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 2) -> Piece(Color.White, PieceType.Rook),
            Position('e', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White,
          inCheck = true
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 2), Position('e', 2)))
        yield assertTrue(result.board.contains(Position('e', 2)))
      },
      test("reject move that does not resolve check") {
        // White king on e1 in check from black rook on e8, white rook on a2 moves elsewhere
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 2) -> Piece(Color.White, PieceType.Rook),
            Position('e', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White,
          inCheck = true
        )
        for err <- Game
            .applyMove(s, Move(Position('a', 2), Position('b', 2)))
            .flip
        yield assertTrue(err.message.contains("check"))
      },
      test("allow king to move out of check") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White,
          inCheck = true
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('d', 1)))
        yield assertTrue(result.board.contains(Position('d', 1)))
      },
      test("reject king moving into check") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for err <- Game
            .applyMove(s, Move(Position('e', 1), Position('d', 1)))
            .flip
        yield assertTrue(err.message.contains("check"))
      },
      test("set inCheck flag when move gives check") {
        // White rook delivers check to black king
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('a', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 1), Position('a', 5)))
        yield assertTrue(result.inCheck)
      },
      test("clear inCheck flag when not in check") {
        for result <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(!result.inCheck)
      }
    ),
    // ─── Castling mechanics ─────────────────────────────────────────────────────
    suite("castling mechanics")(
      test("white king-side: king ends on g1 and rook ends on f1") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('g', 1)))
        yield assertTrue(
          result.board(Position('g', 1)) == Piece(Color.White, PieceType.King),
          result.board(Position('f', 1)) == Piece(Color.White, PieceType.Rook),
          !result.board.contains(Position('e', 1)),
          !result.board.contains(Position('h', 1))
        )
      },
      test("white queen-side: king ends on c1 and rook ends on d1") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('c', 1)))
        yield assertTrue(
          result.board(Position('c', 1)) == Piece(Color.White, PieceType.King),
          result.board(Position('d', 1)) == Piece(Color.White, PieceType.Rook),
          !result.board.contains(Position('e', 1)),
          !result.board.contains(Position('a', 1))
        )
      },
      test("black king-side: king ends on g8 and rook ends on f8") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('h', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 8), Position('g', 8)))
        yield assertTrue(
          result.board(Position('g', 8)) == Piece(Color.Black, PieceType.King),
          result.board(Position('f', 8)) == Piece(Color.Black, PieceType.Rook),
          !result.board.contains(Position('e', 8)),
          !result.board.contains(Position('h', 8))
        )
      },
      test("black queen-side: king ends on c8 and rook ends on d8") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('a', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 8), Position('c', 8)))
        yield assertTrue(
          result.board(Position('c', 8)) == Piece(Color.Black, PieceType.King),
          result.board(Position('d', 8)) == Piece(Color.Black, PieceType.Rook),
          !result.board.contains(Position('e', 8)),
          !result.board.contains(Position('a', 8))
        )
      }
    ),
    // ─── Castling rights tracking ───────────────────────────────────────────────
    suite("castling rights tracking")(
      test("lose both white castling rights when white king moves") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('f', 1)))
        yield assertTrue(
          !result.castlingRights.whiteKingSide,
          !result.castlingRights.whiteQueenSide
        )
      },
      test("lose white king-side right when h1 rook moves") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('h', 1), Position('h', 3)))
        yield assertTrue(
          !result.castlingRights.whiteKingSide,
          result.castlingRights.whiteQueenSide
        )
      },
      test("lose white queen-side right when a1 rook moves") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 1), Position('a', 3)))
        yield assertTrue(
          result.castlingRights.whiteKingSide,
          !result.castlingRights.whiteQueenSide
        )
      },
      test("lose both black castling rights when black king moves") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('a', 8) -> Piece(Color.Black, PieceType.Rook),
            Position('h', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 8), Position('f', 8)))
        yield assertTrue(
          !result.castlingRights.blackKingSide,
          !result.castlingRights.blackQueenSide
        )
      },
      test("lose black king-side right when h8 rook moves") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('h', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('h', 8), Position('h', 6)))
        yield assertTrue(
          !result.castlingRights.blackKingSide,
          result.castlingRights.blackQueenSide
        )
      },
      test("lose black queen-side right when a8 rook moves") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('a', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 8), Position('a', 6)))
        yield assertTrue(
          result.castlingRights.blackKingSide,
          !result.castlingRights.blackQueenSide
        )
      },
      test("lose white king-side right when rook on h1 is captured") {
        // Black rook captures white's h1 rook — white loses king-side right
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook),
            Position('h', 8) -> Piece(Color.Black, PieceType.Rook),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('h', 8), Position('h', 1)))
        yield assertTrue(!result.castlingRights.whiteKingSide)
      },
      test("lose white queen-side right when rook on a1 is captured") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('a', 8) -> Piece(Color.Black, PieceType.Rook),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.Black
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 8), Position('a', 1)))
        yield assertTrue(!result.castlingRights.whiteQueenSide)
      },
      test("lose black king-side right when rook on h8 is captured") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('h', 8) -> Piece(Color.Black, PieceType.Rook),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook),
            Position('e', 1) -> Piece(Color.White, PieceType.King)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('h', 1), Position('h', 8)))
        yield assertTrue(!result.castlingRights.blackKingSide)
      },
      test("lose black queen-side right when rook on a8 is captured") {
        val s = GameState(
          Map(
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('a', 8) -> Piece(Color.Black, PieceType.Rook),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('e', 1) -> Piece(Color.White, PieceType.King)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 1), Position('a', 8)))
        yield assertTrue(!result.castlingRights.blackQueenSide)
      },
      test("lose both white rights after castling") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('g', 1)))
        yield assertTrue(
          !result.castlingRights.whiteKingSide,
          !result.castlingRights.whiteQueenSide
        )
      },
      test("preserve opponent castling rights when own pieces move") {
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('f', 1)))
        yield assertTrue(
          result.castlingRights.blackKingSide,
          result.castlingRights.blackQueenSide
        )
      },
      test("non-rook non-king move preserves all rights") {
        for result <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(
          result.castlingRights.whiteKingSide,
          result.castlingRights.whiteQueenSide,
          result.castlingRights.blackKingSide,
          result.castlingRights.blackQueenSide
        )
      }
    ),
    // ─── Halfmove clock and fullmove number ────────────────────────────────────
    suite("halfmove clock and fullmove number")(
      test("halfmove clock starts at 0 and increments on a knight move") {
        for result <- Game
            .applyMove(initial, Move(Position('g', 1), Position('f', 3)))
        yield assertTrue(result.halfmoveClock == 1)
      },
      test("halfmove clock resets to 0 on a pawn move") {
        for result <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(result.halfmoveClock == 0)
      },
      test("halfmove clock resets to 0 on a capture") {
        val s = GameState(
          Map(
            Position('d', 4) -> Piece(Color.White, PieceType.Queen),
            Position('g', 7) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White,
          halfmoveClock = 5
        )
        for result <- Game
            .applyMove(s, Move(Position('d', 4), Position('g', 7)))
        yield assertTrue(result.halfmoveClock == 0)
      },
      test("halfmove clock accumulates across multiple non-pawn non-capture moves") {
        for
          s1 <- Game.applyMove(initial, Move(Position('g', 1), Position('f', 3)))
          s2 <- Game.applyMove(s1, Move(Position('g', 8), Position('f', 6)))
        yield assertTrue(s1.halfmoveClock == 1, s2.halfmoveClock == 2)
      },
      test("fullmove number stays 1 after White's first move") {
        for result <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(result.fullmoveNumber == 1)
      },
      test("fullmove number increments to 2 after Black's first move") {
        for
          afterWhite <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game
            .applyMove(afterWhite, Move(Position('e', 7), Position('e', 5)))
        yield assertTrue(result.fullmoveNumber == 2)
      },
      test("fullmove number increments only after Black moves") {
        for
          s1 <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          s2 <- Game.applyMove(s1, Move(Position('e', 7), Position('e', 5)))
          s3 <- Game.applyMove(s2, Move(Position('g', 1), Position('f', 3)))
          s4 <- Game.applyMove(s3, Move(Position('b', 8), Position('c', 6)))
        yield assertTrue(
          s1.fullmoveNumber == 1,
          s2.fullmoveNumber == 2,
          s3.fullmoveNumber == 2,
          s4.fullmoveNumber == 3
        )
      },
      test("halfmove clock resets on en passant capture") {
        val s = GameState(
          Map(
            Position('e', 5) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White,
          enPassantTarget = Some(Position('d', 6)),
          halfmoveClock = 3
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(result.halfmoveClock == 0)
      }
    ),
    // ─── Checkmate detection ────────────────────────────────────────────────────
    suite("checkmate detection")(
      test(
        "set Checkmate status when move delivers checkmate (back-rank mate)"
      ) {
        // Black king on g8 boxed in by own pawns, white rook delivers mate on 8th rank
        val s = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 1) -> Piece(Color.White, PieceType.Rook),
            Position('g', 8) -> Piece(Color.Black, PieceType.King),
            Position('f', 7) -> Piece(Color.Black, PieceType.Pawn),
            Position('g', 7) -> Piece(Color.Black, PieceType.Pawn),
            Position('h', 7) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('e', 1), Position('e', 8)))
        yield assertTrue(result.status == GameStatus.Checkmate(Color.White))
      },
      test(
        "set Checkmate status when move delivers checkmate (scholar's mate)"
      ) {
        // White queen takes f7 with bishop supporting from c4, black king on e8
        val s = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 5) -> Piece(Color.White, PieceType.Queen),
            Position('c', 4) -> Piece(Color.White, PieceType.Bishop),
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('f', 7) -> Piece(Color.Black, PieceType.Pawn),
            Position('d', 8) -> Piece(Color.Black, PieceType.Queen),
            Position('f', 8) -> Piece(Color.Black, PieceType.Bishop),
            Position('d', 7) -> Piece(Color.Black, PieceType.Pawn),
            Position('e', 7) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for result <- Game.applyMove(
            s,
            Move(Position('h', 5), Position('f', 7))
          )
        yield assertTrue(result.status == GameStatus.Checkmate(Color.White))
      },
      test("keep Playing status when move gives check but not checkmate") {
        // White rook gives check but black king can escape
        val s = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 2) -> Piece(Color.White, PieceType.Rook),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.White
        )
        for result <- Game
            .applyMove(s, Move(Position('a', 2), Position('e', 2)))
        yield assertTrue(
          result.inCheck,
          result.status == GameStatus.Playing
        )
      },
      test("keep Playing status on a normal move (no check)") {
        for result <- Game
            .applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(result.status == GameStatus.Playing)
      },
      test("reject move when game is already over") {
        val s = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.White,
          status = GameStatus.Checkmate(Color.Black)
        )
        for err <- Game
            .applyMove(s, Move(Position('a', 1), Position('a', 2)))
            .flip
        yield assertTrue(err.message.contains("Game is over"))
      }
    )
  )

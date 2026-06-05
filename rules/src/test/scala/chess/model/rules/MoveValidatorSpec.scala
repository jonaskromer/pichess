package chess.model.rules

import zio.*
import zio.test.*

import chess.model.GameError
import chess.model.board.{BoardState, CastlingRights, GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}

object MoveValidatorSpec extends ZIOSpecDefault:

  // Helpers
  private def state(pieces: (Position, Piece)*): GameState =
    GameState(pieces.toMap, Color.White)

  private def blackState(pieces: (Position, Piece)*): GameState =
    GameState(pieces.toMap, Color.Black)

  private def pos(col: Char, row: Int): Position = Position(col, row)

  private val WP = Piece(Color.White, PieceType.Pawn)
  private val BP = Piece(Color.Black, PieceType.Pawn)
  private val WR = Piece(Color.White, PieceType.Rook)
  private val BR = Piece(Color.Black, PieceType.Rook)
  private val WB = Piece(Color.White, PieceType.Bishop)
  private val BB = Piece(Color.Black, PieceType.Bishop)
  private val WQ = Piece(Color.White, PieceType.Queen)
  private val BQ = Piece(Color.Black, PieceType.Queen)
  private val WN = Piece(Color.White, PieceType.Knight)
  private val BN = Piece(Color.Black, PieceType.Knight)
  private val WK = Piece(Color.White, PieceType.King)
  private val BK = Piece(Color.Black, PieceType.King)

  def spec = suite("MoveValidator")(
    // ─── Pawn (White) ───────────────────────────────────────────────────────────
    suite("white pawn")(
      test("allow one square forward") {
        val s = state(pos('e', 2) -> WP)
        MoveValidator
          .validate(s, Move(pos('e', 2), pos('e', 3)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow two squares forward from rank 2") {
        val s = state(pos('e', 2) -> WP)
        MoveValidator
          .validate(s, Move(pos('e', 2), pos('e', 4)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject two-square advance from non-starting rank") {
        val s = state(pos('e', 3) -> WP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 3), pos('e', 5)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject two-square advance when intermediate square is blocked") {
        val s = state(pos('e', 2) -> WP, pos('e', 3) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 2), pos('e', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test(
        "reject two-square advance when destination is occupied but path is clear"
      ) {
        val s = state(pos('e', 2) -> WP, pos('e', 4) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 2), pos('e', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move forward when destination is occupied") {
        val s = state(pos('e', 3) -> WP, pos('e', 4) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 3), pos('e', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("allow diagonal capture of enemy piece") {
        val s = state(pos('e', 3) -> WP, pos('f', 4) -> BP)
        MoveValidator
          .validate(s, Move(pos('e', 3), pos('f', 4)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject diagonal move to empty square") {
        val s = state(pos('e', 3) -> WP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 3), pos('f', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject moving backward") {
        val s = state(pos('e', 3) -> WP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 3), pos('e', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject moving sideways") {
        val s = state(pos('e', 3) -> WP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 3), pos('f', 3)))
            .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    // ─── Pawn (Black) ───────────────────────────────────────────────────────────
    suite("black pawn")(
      test("allow one square forward (toward rank 1)") {
        val s = blackState(pos('e', 7) -> BP)
        MoveValidator
          .validate(s, Move(pos('e', 7), pos('e', 6)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow two squares forward from rank 7") {
        val s = blackState(pos('e', 7) -> BP)
        MoveValidator
          .validate(s, Move(pos('e', 7), pos('e', 5)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject two-square advance from non-starting rank") {
        val s = blackState(pos('e', 6) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 6), pos('e', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("allow diagonal capture of enemy piece") {
        val s = blackState(pos('e', 7) -> BP, pos('f', 6) -> WP)
        MoveValidator
          .validate(s, Move(pos('e', 7), pos('f', 6)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      }
    ),
    // ─── Rook ───────────────────────────────────────────────────────────────────
    suite("rook")(
      test("allow horizontal move on a clear rank") {
        val s = state(pos('a', 1) -> WR)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('h', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow vertical move on a clear file") {
        val s = state(pos('a', 1) -> WR)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('a', 8)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject diagonal move") {
        val s = state(pos('a', 1) -> WR)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('b', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject knight-leap shape") {
        val s = state(pos('a', 1) -> WR)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('c', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject irregular direction") {
        val s = state(pos('a', 1) -> WR)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('c', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move when path is blocked") {
        val s = state(pos('a', 1) -> WR, pos('c', 1) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('h', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("allow capture at the end of a clear path") {
        val s = state(pos('a', 1) -> WR, pos('h', 1) -> BP)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('h', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      }
    ),
    // ─── Bishop ─────────────────────────────────────────────────────────────────
    suite("bishop")(
      test("allow diagonal move on a clear path") {
        val s = state(pos('a', 1) -> WB)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('d', 4)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow diagonal move in the other direction") {
        val s = state(pos('d', 4) -> WB)
        MoveValidator
          .validate(s, Move(pos('d', 4), pos('a', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject horizontal move") {
        val s = state(pos('a', 1) -> WB)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('h', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject vertical move") {
        val s = state(pos('a', 1) -> WB)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('a', 8)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject knight-leap shape") {
        val s = state(pos('a', 1) -> WB)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('c', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject irregular direction") {
        val s = state(pos('a', 1) -> WB)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('c', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move to own square (zero move)") {
        val s = state(pos('d', 4) -> WB)
        for exit <- MoveValidator
            .validate(s, Move(pos('d', 4), pos('d', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move when diagonal path is blocked") {
        val s = state(pos('a', 1) -> WB, pos('b', 2) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('d', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    // ─── Queen ──────────────────────────────────────────────────────────────────
    suite("queen")(
      test("allow horizontal move") {
        val s = state(pos('d', 1) -> WQ)
        MoveValidator
          .validate(s, Move(pos('d', 1), pos('h', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow vertical move") {
        val s = state(pos('d', 1) -> WQ)
        MoveValidator
          .validate(s, Move(pos('d', 1), pos('d', 8)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow diagonal move") {
        val s = state(pos('d', 1) -> WQ)
        MoveValidator
          .validate(s, Move(pos('d', 1), pos('g', 4)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject knight-leap shape") {
        val s = state(pos('d', 1) -> WQ)
        for exit <- MoveValidator
            .validate(s, Move(pos('d', 1), pos('f', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject irregular direction") {
        val s = state(pos('d', 1) -> WQ)
        for exit <- MoveValidator
            .validate(s, Move(pos('d', 1), pos('e', 3)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move to own square (zero move)") {
        val s = state(pos('d', 1) -> WQ)
        for exit <- MoveValidator
            .validate(s, Move(pos('d', 1), pos('d', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move when path is blocked") {
        val s = state(pos('d', 1) -> WQ, pos('f', 1) -> BP)
        for exit <- MoveValidator
            .validate(s, Move(pos('d', 1), pos('h', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    // ─── Knight ─────────────────────────────────────────────────────────────────
    suite("knight")(
      test("allow L-shape (2 forward, 1 side)") {
        val s = state(pos('g', 1) -> WN)
        MoveValidator
          .validate(s, Move(pos('g', 1), pos('f', 3)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow L-shape (1 forward, 2 side)") {
        val s = state(pos('g', 1) -> WN)
        MoveValidator
          .validate(s, Move(pos('g', 1), pos('h', 3)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow L-shape (2 side, 1 forward)") {
        val s = state(pos('b', 1) -> WN)
        MoveValidator
          .validate(s, Move(pos('b', 1), pos('d', 2)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow jumping over pieces") {
        val s = state(pos('g', 1) -> WN, pos('g', 2) -> BP, pos('f', 2) -> BP)
        MoveValidator
          .validate(s, Move(pos('g', 1), pos('f', 3)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject straight move") {
        val s = state(pos('g', 1) -> WN)
        for exit <- MoveValidator
            .validate(s, Move(pos('g', 1), pos('g', 3)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject diagonal one step") {
        val s = state(pos('g', 1) -> WN)
        for exit <- MoveValidator
            .validate(s, Move(pos('g', 1), pos('h', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject horizontal move") {
        val s = state(pos('a', 1) -> WN)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('h', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject irregular direction") {
        val s = state(pos('a', 1) -> WN)
        for exit <- MoveValidator
            .validate(s, Move(pos('a', 1), pos('d', 4)))
            .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    // ─── King ───────────────────────────────────────────────────────────────────
    suite("king")(
      test("allow one square horizontally") {
        val s = state(pos('e', 1) -> WK)
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('f', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow one square vertically") {
        val s = state(pos('e', 1) -> WK)
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('e', 2)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow one square diagonally") {
        val s = state(pos('e', 1) -> WK)
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('f', 2)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject two squares") {
        val s = state(pos('e', 1) -> WK)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject knight-leap shape") {
        val s = state(pos('e', 1) -> WK)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 2)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject move to own square (zero move)") {
        val s = state(pos('e', 1) -> WK)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('e', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    // ─── Own piece capture ──────────────────────────────────────────────────────
    test("reject capture of same color piece") {
      val s = state(pos('a', 1) -> WR, pos('h', 1) -> WP)
      for exit <- MoveValidator.validate(s, Move(pos('a', 1), pos('h', 1))).exit
      yield assertTrue(exit.isFailure)
    },
    // ─── En passant ─────────────────────────────────────────────────────────────
    suite("en passant")(
      test("allow white pawn en passant capture") {
        val s = GameState(
          Map(pos('e', 5) -> WP, pos('d', 5) -> BP),
          Color.White,
          enPassantTarget = Some(pos('d', 6))
        )
        MoveValidator
          .validate(s, Move(pos('e', 5), pos('d', 6)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("allow black pawn en passant capture") {
        val s = GameState(
          Map(pos('d', 4) -> BP, pos('e', 4) -> WP),
          Color.Black,
          enPassantTarget = Some(pos('e', 3))
        )
        MoveValidator
          .validate(s, Move(pos('d', 4), pos('e', 3)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test("reject diagonal to empty square without en passant target") {
        val s = state(pos('e', 5) -> WP)
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 5), pos('d', 6)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject diagonal to wrong en passant target") {
        val s = GameState(
          Map(pos('e', 5) -> WP),
          Color.White,
          enPassantTarget = Some(pos('f', 6))
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 5), pos('d', 6)))
            .exit
        yield assertTrue(exit.isFailure)
      }
    ),
    // ─── Castling validation ──────────────────────────────────────────────────
    suite("castling")(
      test(
        "allow white king-side castling when path is clear and rights exist"
      ) {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR
          ),
          Color.White
        )
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('g', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test(
        "allow white queen-side castling when path is clear and rights exist"
      ) {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('a', 1) -> WR
          ),
          Color.White
        )
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('c', 1)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test(
        "allow black king-side castling when path is clear and rights exist"
      ) {
        val s = GameState(
          Map(
            pos('e', 8) -> BK,
            pos('h', 8) -> BR
          ),
          Color.Black
        )
        MoveValidator
          .validate(s, Move(pos('e', 8), pos('g', 8)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test(
        "allow black queen-side castling when path is clear and rights exist"
      ) {
        val s = GameState(
          Map(
            pos('e', 8) -> BK,
            pos('a', 8) -> BR
          ),
          Color.Black
        )
        MoveValidator
          .validate(s, Move(pos('e', 8), pos('c', 8)))
          .exit
          .map(e => assertTrue(e.isSuccess))
      },
      test(
        "reject castling when pieces are between king and rook (king-side)"
      ) {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('f', 1) -> WB,
            pos('h', 1) -> WR
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test(
        "reject castling when pieces are between king and rook (queen-side)"
      ) {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('b', 1) -> WN,
            pos('a', 1) -> WR
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('c', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject castling when king has lost castling rights") {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR
          ),
          Color.White,
          castlingRights = CastlingRights(
            whiteKingSide = false,
            whiteQueenSide = false,
            blackKingSide = true,
            blackQueenSide = true
          )
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject queen-side castling when only that right is lost") {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('a', 1) -> WR
          ),
          Color.White,
          castlingRights = CastlingRights(
            whiteKingSide = true,
            whiteQueenSide = false,
            blackKingSide = true,
            blackQueenSide = true
          )
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('c', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject castling when king is in check") {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR,
            pos('e', 8) -> BR
          ),
          Color.White,
          inCheck = true
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test(
        "reject castling when king passes through attacked square (king-side)"
      ) {
        // f1 is attacked by black bishop on b5
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR,
            pos('b', 5) -> BB
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject castling when king lands on attacked square") {
        // g1 is attacked by black bishop on d4
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR,
            pos('d', 4) -> BB
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test(
        "reject castling when king passes through attacked square (queen-side)"
      ) {
        // d1 is attacked by black rook on d8
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('a', 1) -> WR,
            pos('d', 8) -> BR
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('c', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("reject castling when rook is missing") {
        val s = GameState(
          Map(pos('e', 1) -> WK),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isFailure)
      },
      test(
        "allow queen-side castling when only the rook's path (b1) is attacked"
      ) {
        // Black rook on b8 attacks the b-file; b1 is the square the ROOK
        // crosses during O-O-O but NOT a square the king passes through.
        // King path is e1 → d1 → c1; none of those are attacked.
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('a', 1) -> WR,
            pos('b', 8) -> BR
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('c', 1)))
            .exit
        yield assertTrue(exit.isSuccess)
      },
      test("allow king-side castling when the rook itself is attacked") {
        // Black rook on h8 x-rays h1 through the empty h-file. The white
        // rook is under attack but the king's path (e1 → f1 → g1) is safe,
        // so castling is legal.
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR,
            pos('h', 8) -> BR
          ),
          Color.White
        )
        for exit <- MoveValidator
            .validate(s, Move(pos('e', 1), pos('g', 1)))
            .exit
        yield assertTrue(exit.isSuccess)
      }
    ),
    // ─── Source square / turn validation ────────────────────────────────────────
    suite("source and turn")(
      test("error includes source position when square is empty") {
        val s = state(pos('a', 1) -> WR)
        for err <- MoveValidator
            .validate(s, Move(pos('e', 4), pos('e', 5)))
            .flip
        yield assertTrue(err.message.contains("e4"))
      },
      test("error mentions piece color when moving opponent's piece") {
        val s = state(pos('e', 7) -> BP)
        for err <- MoveValidator
            .validate(s, Move(pos('e', 7), pos('e', 6)))
            .flip
        yield assertTrue(err.message.contains("Black"))
      }
    ),
    // ─── Check detection ────────────────────────────────────────────────────────
    suite("isInCheck")(
      test("detect rook giving check") {
        val board = Map(pos('e', 1) -> WK, pos('e', 8) -> BR).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.White))
      },
      test("no check when path is blocked") {
        val board =
          Map(pos('e', 1) -> WK, pos('e', 4) -> WP, pos('e', 8) -> BR).toMap
        assertTrue(!MoveValidator.isInCheck(board, Color.White))
      },
      test("detect bishop giving check") {
        val board = Map(pos('e', 1) -> WK, pos('h', 4) -> BB).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.White))
      },
      test("detect queen giving check diagonally") {
        val board = Map(pos('e', 1) -> WK, pos('a', 5) -> BQ).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.White))
      },
      test("detect queen giving check on file") {
        val board = Map(pos('e', 1) -> WK, pos('e', 6) -> BQ).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.White))
      },
      test("detect knight giving check") {
        val board = Map(pos('e', 1) -> WK, pos('f', 3) -> BN).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.White))
      },
      test("detect pawn giving check") {
        val board = Map(pos('e', 4) -> WK, pos('f', 5) -> BP).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.White))
      },
      test("pawn does not give check from behind") {
        val board = Map(pos('e', 4) -> WK, pos('f', 3) -> BP).toMap
        assertTrue(!MoveValidator.isInCheck(board, Color.White))
      },
      test("no check on initial board") {
        assertTrue(
          !MoveValidator.isInCheck(GameState.initial.board, Color.White)
        )
        assertTrue(
          !MoveValidator.isInCheck(GameState.initial.board, Color.Black)
        )
      },
      test("detect black king in check") {
        val board = Map(pos('e', 8) -> BK, pos('e', 1) -> WR).toMap
        assertTrue(MoveValidator.isInCheck(board, Color.Black))
      }
    ),
    // ─── hasLegalMove ───────────────────────────────────────────────────────────
    suite("hasLegalMove")(
      test("return true for initial board") {
        for result <- MoveValidator.hasLegalMove(GameState.initial)
        yield assertTrue(result)
      },
      test("return false for back-rank mate position") {
        // Black king on g8, black pawns on f7/g7/h7, white rook delivers mate on e8
        val s = GameState(
          Map(
            pos('g', 8) -> BK,
            pos('f', 7) -> BP,
            pos('g', 7) -> BP,
            pos('h', 7) -> BP,
            pos('e', 8) -> WR,
            pos('a', 1) -> WK
          ),
          Color.Black,
          inCheck = true
        )
        for result <- MoveValidator.hasLegalMove(s)
        yield assertTrue(!result)
      },
      test("return true when in check but king can escape") {
        // Black king on e8, white rook on e1 giving check, but king can move to d8/f8
        val s = GameState(
          Map(
            pos('e', 8) -> BK,
            pos('e', 1) -> WR,
            pos('a', 1) -> WK
          ),
          Color.Black,
          inCheck = true
        )
        for result <- MoveValidator.hasLegalMove(s)
        yield assertTrue(result)
      },
      test("return false for smothered mate") {
        // Black king on h8, black rook on g8, black pawn on g7/h7, white knight on f7
        val s = GameState(
          Map(
            pos('h', 8) -> BK,
            pos('g', 8) -> BR,
            pos('g', 7) -> BP,
            pos('h', 7) -> BP,
            pos('f', 7) -> WN,
            pos('a', 1) -> WK
          ),
          Color.Black,
          inCheck = true
        )
        for result <- MoveValidator.hasLegalMove(s)
        yield assertTrue(!result)
      },
      test("return true when only legal move is a pawn promotion") {
        // White king on h1, hemmed in by own pawns on g2/h2 which are blocked
        // by black pawns on g3/h3. Only legal move is pawn on a7 promoting to a8.
        val s = GameState(
          Map(
            pos('h', 1) -> WK,
            pos('g', 2) -> WP,
            pos('h', 2) -> WP,
            pos('g', 3) -> BP,
            pos('h', 3) -> BP,
            pos('a', 7) -> WP,
            pos('e', 8) -> BK
          ),
          Color.White
        )
        for result <- MoveValidator.hasLegalMove(s)
        yield assertTrue(result)
      }
    ),

    // ─── legalMovesFrom ─────────────────────────────────────────────────────
    suite("legalMovesFrom")(
      test("empty for an empty square") {
        for result <- MoveValidator.legalMovesFrom(state(), pos('e', 4))
        yield assertTrue(result.isEmpty)
      },
      test("empty when the piece belongs to the inactive color") {
        // White is to move, ask about a black piece — must come back empty
        // even though that black piece has legal moves on its own turn.
        val s = state(pos('e', 7) -> BP, pos('h', 1) -> WK, pos('a', 8) -> BK)
        for result <- MoveValidator.legalMovesFrom(s, pos('e', 7))
        yield assertTrue(result.isEmpty)
      },
      test("white pawn on starting rank can advance one or two squares") {
        val s = state(pos('e', 2) -> WP, pos('e', 1) -> WK, pos('e', 8) -> BK)
        for result <- MoveValidator.legalMovesFrom(s, pos('e', 2))
        yield assertTrue(
          result.toSet == Set(pos('e', 3), pos('e', 4))
        )
      },
      test("knight on b1 reaches its three open squares") {
        // Knight on b1 attacks a3, c3, d2. With only the king elsewhere,
        // all three are legal (none of them expose the king to check).
        val s = state(
          pos('b', 1) -> WN,
          pos('e', 1) -> WK,
          pos('e', 8) -> BK
        )
        for result <- MoveValidator.legalMovesFrom(s, pos('b', 1))
        yield assertTrue(
          result.toSet == Set(pos('a', 3), pos('c', 3), pos('d', 2))
        )
      },
      test("pinned piece has no legal moves") {
        // White rook on e2 is pinned to the king on e1 by a black queen on e8.
        // Moving anywhere off the e-file would expose the king to check.
        val s = state(
          pos('e', 1) -> WK,
          pos('e', 2) -> WR,
          pos('e', 8) -> BQ,
          pos('a', 8) -> BK
        )
        for result <- MoveValidator.legalMovesFrom(s, pos('e', 2))
        yield assertTrue(
          // Only legal moves are along the pin ray (e3..e7) — capturing
          // the queen on e8 is also legal since the rook stays pinned.
          result.toSet == Set(
            pos('e', 3),
            pos('e', 4),
            pos('e', 5),
            pos('e', 6),
            pos('e', 7),
            pos('e', 8)
          )
        )
      },
      test("white can castle kingside when the lane is clear") {
        val s = GameState(
          Map(
            pos('e', 1) -> WK,
            pos('h', 1) -> WR,
            pos('e', 8) -> BK
          ),
          Color.White
        )
        for result <- MoveValidator.legalMovesFrom(s, pos('e', 1))
        yield assertTrue(result.contains(pos('g', 1)))
      },
      test("en-passant target appears in the moving pawn's legal squares") {
        // Black pawn on d5, white pawn on e5, en-passant target d6 (set when
        // black just played d7-d5). White's exd6 e.p. should appear.
        val s = GameState(
          Map(
            pos('e', 5) -> WP,
            pos('d', 5) -> BP,
            pos('e', 1) -> WK,
            pos('e', 8) -> BK
          ),
          Color.White,
          enPassantTarget = Some(pos('d', 6))
        )
        for result <- MoveValidator.legalMovesFrom(s, pos('e', 5))
        yield assertTrue(result.contains(pos('d', 6)))
      },
      test("promotion squares deduped — one entry per destination") {
        // White pawn on a7 with empty a8 — candidateMoves emits one move
        // per promotion piece (Q/R/B/N). The destination should appear once.
        val s = state(pos('a', 7) -> WP, pos('e', 1) -> WK, pos('e', 8) -> BK)
        for result <- MoveValidator.legalMovesFrom(s, pos('a', 7))
        yield assertTrue(result.count(_ == pos('a', 8)) == 1)
      }
    ),

    // ─── attackersOf ────────────────────────────────────────────────────────
    suite("attackersOf")(
      test("empty when no piece of the asked color attacks the square") {
        val board = Map(pos('a', 1) -> WK, pos('h', 8) -> BK)
        assertTrue(
          MoveValidator
            .attackersOf(board, pos('e', 4), Color.White)
            .isEmpty
        )
      },
      test("rook attacks along its rank and file") {
        val board = Map(pos('a', 1) -> WR)
        assertTrue(
          MoveValidator
            .attackersOf(board, pos('a', 5), Color.White)
            .toSet == Set(pos('a', 1)),
          MoveValidator
            .attackersOf(board, pos('e', 1), Color.White)
            .toSet == Set(pos('a', 1))
        )
      },
      test("knight L-pattern attacks") {
        val board = Map(pos('e', 4) -> WN)
        // From e4 a knight attacks f6, d6, c5, c3, d2, f2, g3, g5
        val expected = Set(
          pos('f', 6), pos('d', 6), pos('c', 5), pos('c', 3),
          pos('d', 2), pos('f', 2), pos('g', 3), pos('g', 5)
        )
        assertTrue(
          expected.forall(sq =>
            MoveValidator.attackersOf(board, sq, Color.White) == List(pos('e', 4))
          )
        )
      },
      test("bishop attacks diagonals; blocked by intervening piece") {
        // Use a knight as the blocker so its attack pattern doesn't muddy
        // the assertions about the bishop's reach.
        val board = Map(
          pos('c', 1) -> WB,
          pos('e', 3) -> WN
        )
        assertTrue(
          // c1 attacks d2 and e3 (capture stops the ray) but not f4.
          MoveValidator.attackersOf(board, pos('d', 2), Color.White) ==
            List(pos('c', 1)),
          MoveValidator
            .attackersOf(board, pos('e', 3), Color.White) == List(pos('c', 1)),
          // Bishop's ray from c1 stops at e3, so f4 isn't attacked by the
          // bishop. The knight on e3 doesn't reach f4 either (its squares
          // are c2/c4/d1/d5/f1/f5/g2/g4).
          MoveValidator
            .attackersOf(board, pos('f', 4), Color.White)
            .isEmpty
        )
      },
      test("pawn attacks diagonally forward; not the square directly ahead") {
        val board = Map(pos('e', 4) -> WP)
        assertTrue(
          MoveValidator.attackersOf(board, pos('d', 5), Color.White) ==
            List(pos('e', 4)),
          MoveValidator.attackersOf(board, pos('f', 5), Color.White) ==
            List(pos('e', 4)),
          // The square directly ahead is a push, not an attack.
          MoveValidator
            .attackersOf(board, pos('e', 5), Color.White)
            .isEmpty
        )
      },
      test("multiple attackers of the same color all surface") {
        // Rook on e1 attacks e3 along the e-file; bishop on c1 attacks e3
        // along the c1-h6 diagonal (c1, d2, e3).
        val board = Map(
          pos('e', 1) -> WR,
          pos('c', 1) -> WB
        )
        assertTrue(
          MoveValidator
            .attackersOf(board, pos('e', 3), Color.White)
            .toSet == Set(pos('e', 1), pos('c', 1))
        )
      },
      test("filters by attacker color — only the asked color is returned") {
        val board = Map(pos('a', 1) -> WR, pos('a', 8) -> BR)
        assertTrue(
          MoveValidator
            .attackersOf(board, pos('a', 5), Color.White) == List(pos('a', 1)),
          MoveValidator
            .attackersOf(board, pos('a', 5), Color.Black) == List(pos('a', 8))
        )
      }
    ),
    suite("legalCapturesAndQuiets")(
      test("partition is exhaustive — captures ∪ quiets equals legalDestinationsIndex") {
        // Simple mid-game-ish position with a mix of captures + quiet moves.
        // White rook on a1, black pawn on a5 (rook can capture or quiet move).
        val s = state(
          pos('a', 1) -> WR,
          pos('a', 5) -> BP,
          pos('e', 1) -> WK,
          pos('e', 8) -> BK,
        )
        for
          all                <- MoveValidator.legalDestinationsIndex(s)
          (captures, quiets) <- MoveValidator.legalCapturesAndQuiets(s)
        yield
          // Union of the two partitions must equal the full index;
          // both sides keyed by from-square with deduplication.
          val unionByFrom: Map[Position, Set[Position]] =
            (captures.toSeq ++ quiets.toSeq)
              .groupMapReduce(_._1)(_._2.toSet)(_ ++ _)
          val expected: Map[Position, Set[Position]] =
            all.view.mapValues(_.toSet).toMap
          assertTrue(unionByFrom == expected)
      },
      test("partition is disjoint — no destination appears in both sub-maps for the same source") {
        // Pure quiet position would leave captures empty; pure
        // capture position would leave quiets empty. A mix tests
        // the disjointness invariant.
        val s = state(
          pos('a', 1) -> WR,
          pos('a', 5) -> BP,
          pos('e', 1) -> WK,
          pos('e', 8) -> BK,
        )
        for (captures, quiets) <- MoveValidator.legalCapturesAndQuiets(s)
        yield
          val overlap = captures.iterator.flatMap { case (from, dests) =>
            val q = quiets.getOrElse(from, Nil).toSet
            dests.filter(q.contains)
          }
          assertTrue(overlap.isEmpty)
      },
      test("destination with an enemy piece classifies as capture") {
        val s = state(
          pos('a', 1) -> WR,
          pos('a', 5) -> BP,
          pos('e', 1) -> WK,
          pos('e', 8) -> BK,
        )
        for (captures, _) <- MoveValidator.legalCapturesAndQuiets(s)
        yield assertTrue(
          captures.get(pos('a', 1)).exists(_.contains(pos('a', 5)))
        )
      },
      test("destination with no piece classifies as quiet") {
        val s = state(
          pos('a', 1) -> WR,
          pos('a', 5) -> BP,
          pos('e', 1) -> WK,
          pos('e', 8) -> BK,
        )
        for (_, quiets) <- MoveValidator.legalCapturesAndQuiets(s)
        yield assertTrue(
          quiets.get(pos('a', 1)).exists(_.contains(pos('a', 2)))
        )
      },
      test("en passant target classifies as a capture (no piece on dest square)") {
        // White pawn on e5, black just played d7-d5 → EP square is d6.
        // White's exd6 ep is a capture even though d6 is empty.
        val board = BoardState.fromMap(
          Map(pos('e', 5) -> WP, pos('d', 5) -> BP, pos('e', 1) -> WK, pos('e', 8) -> BK)
        )
        val s = GameState(
          board           = board,
          activeColor     = Color.White,
          enPassantTarget = Some(pos('d', 6)),
        )
        for (captures, quiets) <- MoveValidator.legalCapturesAndQuiets(s)
        yield assertTrue(
          captures.get(pos('e', 5)).exists(_.contains(pos('d', 6))),
          !quiets.get(pos('e', 5)).exists(_.contains(pos('d', 6))),
        )
      },
      test("isLegalMoveSync returns false for an empty source square") {
        val s = state(pos('e', 1) -> WK, pos('e', 8) -> BK)
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('d', 4), pos('d', 5)))
        )
      },
      test("isLegalMoveSync returns false when piece is the inactive color") {
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('a', 7) -> BR,
        )
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('a', 7), pos('a', 6)))
        )
      },
      test("isLegalMoveSync returns false when capturing own piece") {
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('a', 1) -> WR, pos('a', 7) -> WP,
        )
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('a', 1), pos('a', 7)))
        )
      },
      test("isLegalMoveSync returns true for a legal pawn push") {
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('e', 2) -> WP,
        )
        assertTrue(
          MoveValidator.isLegalMoveSync(s, Move(pos('e', 2), pos('e', 4)))
        )
      },
      test("isLegalMoveSync returns false for a pawn pushing into an occupied square") {
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('e', 2) -> WP, pos('e', 3) -> BP,
        )
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('e', 2), pos('e', 3)))
        )
      },
      test("isLegalMoveSync rejects a pawn diagonal move to an empty (non-EP) square") {
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('e', 2) -> WP,
        )
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('e', 2), pos('d', 3)))
        )
      },
      test("isLegalMoveSync rejects a pawn 'sideways' move") {
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('e', 2) -> WP,
        )
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('e', 2), pos('f', 2)))
        )
      },
      test("isLegalMoveSync rejects castling when castling rights are lost") {
        val s = state(
          pos('e', 1) -> WK, pos('h', 1) -> WR, pos('e', 8) -> BK,
        ).copy(castlingRights = CastlingRights(false, false, false, false))
        assertTrue(
          !MoveValidator.isLegalMoveSync(s, Move(pos('e', 1), pos('g', 1)))
        )
      },
      test("isLegalMoveSync accepts a legal white castle") {
        val s = state(
          pos('e', 1) -> WK, pos('h', 1) -> WR, pos('e', 8) -> BK,
        )
        assertTrue(
          MoveValidator.isLegalMoveSync(s, Move(pos('e', 1), pos('g', 1)))
        )
      },
      test("isLegalMoveSync accepts a legal black castle (exercises the rank=8 branch)") {
        val s = blackState(
          pos('e', 1) -> WK, pos('e', 8) -> BK, pos('h', 8) -> BR,
        )
        assertTrue(
          MoveValidator.isLegalMoveSync(s, Move(pos('e', 8), pos('g', 8)))
        )
      },
      test("legalDestinationsIndexSync matches the IO variant on a tactical fixture") {
        // KiwiPete-style position: every code path matters (pawn,
        // sliding pieces, king with castling).
        val s = state(
          pos('e', 1) -> WK,
          pos('e', 8) -> BK,
          pos('a', 1) -> WR,
          pos('a', 5) -> BP,
        )
        for io <- MoveValidator.legalDestinationsIndex(s)
        yield assertTrue(
          MoveValidator.legalDestinationsIndexSync(s) == io
        )
      },
      test("legalMovesFromSync returns Nil on an empty square") {
        val s = state(pos('e', 1) -> WK, pos('e', 8) -> BK)
        assertTrue(MoveValidator.legalMovesFromSync(s, pos('d', 4)) == Nil)
      },
      test("legalMovesFromSync returns Nil when the piece is the inactive color") {
        // White to move; ask about a black piece.
        val s = state(
          pos('e', 1) -> WK, pos('e', 8) -> BK,
          pos('a', 6) -> BR,
        )
        assertTrue(MoveValidator.legalMovesFromSync(s, pos('a', 6)) == Nil)
      },
      test("legalMovesFromSync returns the same destinations as the IO variant") {
        // Spot-check a pawn — covers the `Some(piece)` legal path.
        val s = state(pos('g', 2) -> WP, pos('e', 1) -> WK, pos('e', 8) -> BK)
        for io <- MoveValidator.legalMovesFrom(s, pos('g', 2))
        yield assertTrue(
          MoveValidator.legalMovesFromSync(s, pos('g', 2)).toSet == io.toSet
        )
      },
      test("source with only captures (no quiets) emits no quiet entry for that piece") {
        // White knight on c3 with all 8 knight squares occupied by
        // enemy pieces (and own king elsewhere). Every legal knight
        // move is a capture; nothing should land in the quiets map.
        val s = state(
          pos('c', 3) -> WN,
          pos('a', 2) -> BP,
          pos('a', 4) -> BP,
          pos('b', 1) -> BP,
          pos('b', 5) -> BP,
          pos('d', 1) -> BP,
          pos('d', 5) -> BP,
          pos('e', 2) -> BP,
          pos('e', 4) -> BP,
          pos('h', 1) -> WK,
          pos('h', 8) -> BK,
        )
        for (captures, quiets) <- MoveValidator.legalCapturesAndQuiets(s)
        yield assertTrue(
          captures.contains(pos('c', 3)),
          !quiets.contains(pos('c', 3)),
        )
      },
      test("position with no legal moves returns two empty maps") {
        // Lone king in stalemate: no moves of any kind.
        val s = blackState(
          pos('h', 1) -> BK,
          pos('f', 2) -> WK,
          pos('g', 3) -> WQ,
        )
        for (captures, quiets) <- MoveValidator.legalCapturesAndQuiets(s)
        yield assertTrue(captures.isEmpty, quiets.isEmpty)
      },
    )
  )

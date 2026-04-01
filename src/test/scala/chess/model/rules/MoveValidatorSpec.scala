package chess.model.rules

import chess.model.GameError
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{CastlingRights, GameState, Move, Position}
import zio.*
import zio.test.*

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
          .as(assertTrue(true))
      },
      test("allow two squares forward from rank 2") {
        val s = state(pos('e', 2) -> WP)
        MoveValidator
          .validate(s, Move(pos('e', 2), pos('e', 4)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      },
      test("allow two squares forward from rank 7") {
        val s = blackState(pos('e', 7) -> BP)
        MoveValidator
          .validate(s, Move(pos('e', 7), pos('e', 5)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      }
    ),
    // ─── Rook ───────────────────────────────────────────────────────────────────
    suite("rook")(
      test("allow horizontal move on a clear rank") {
        val s = state(pos('a', 1) -> WR)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('h', 1)))
          .as(assertTrue(true))
      },
      test("allow vertical move on a clear file") {
        val s = state(pos('a', 1) -> WR)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('a', 8)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      }
    ),
    // ─── Bishop ─────────────────────────────────────────────────────────────────
    suite("bishop")(
      test("allow diagonal move on a clear path") {
        val s = state(pos('a', 1) -> WB)
        MoveValidator
          .validate(s, Move(pos('a', 1), pos('d', 4)))
          .as(assertTrue(true))
      },
      test("allow diagonal move in the other direction") {
        val s = state(pos('d', 4) -> WB)
        MoveValidator
          .validate(s, Move(pos('d', 4), pos('a', 1)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      },
      test("allow vertical move") {
        val s = state(pos('d', 1) -> WQ)
        MoveValidator
          .validate(s, Move(pos('d', 1), pos('d', 8)))
          .as(assertTrue(true))
      },
      test("allow diagonal move") {
        val s = state(pos('d', 1) -> WQ)
        MoveValidator
          .validate(s, Move(pos('d', 1), pos('g', 4)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      },
      test("allow L-shape (1 forward, 2 side)") {
        val s = state(pos('g', 1) -> WN)
        MoveValidator
          .validate(s, Move(pos('g', 1), pos('h', 3)))
          .as(assertTrue(true))
      },
      test("allow L-shape (2 side, 1 forward)") {
        val s = state(pos('b', 1) -> WN)
        MoveValidator
          .validate(s, Move(pos('b', 1), pos('d', 2)))
          .as(assertTrue(true))
      },
      test("allow jumping over pieces") {
        val s = state(pos('g', 1) -> WN, pos('g', 2) -> BP, pos('f', 2) -> BP)
        MoveValidator
          .validate(s, Move(pos('g', 1), pos('f', 3)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      },
      test("allow one square vertically") {
        val s = state(pos('e', 1) -> WK)
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('e', 2)))
          .as(assertTrue(true))
      },
      test("allow one square diagonally") {
        val s = state(pos('e', 1) -> WK)
        MoveValidator
          .validate(s, Move(pos('e', 1), pos('f', 2)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
      },
      test("allow black pawn en passant capture") {
        val s = GameState(
          Map(pos('d', 4) -> BP, pos('e', 4) -> WP),
          Color.Black,
          enPassantTarget = Some(pos('e', 3))
        )
        MoveValidator
          .validate(s, Move(pos('d', 4), pos('e', 3)))
          .as(assertTrue(true))
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
          .as(assertTrue(true))
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
          .as(assertTrue(true))
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
          .as(assertTrue(true))
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
          .as(assertTrue(true))
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
    )
  )

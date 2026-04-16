package chess.model.rules

import chess.codec.FenParserRegex
import chess.controller.MoveParser
import chess.model.GameError
import chess.model.board.{GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.{IO, ZIO}
import zio.test.*

/** Multi-move behavioral tests exercising scenarios that exist white-side in
  * the other specs but were missing from the black side.
  *
  * Unit-level mechanics (single-move castling for all four options, promotion
  * for both colors, check detection for both kings) are already covered by
  * [[chess.model.rules.GameSpec]]. This spec fills the gap where the
  * higher-level sequence specs (realistic games, PGN replays, combinatorial
  * state tests) only ever exercised white-side variants.
  */
object ColorSymmetrySpec extends ZIOSpecDefault:

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

  def spec = suite("Color-symmetry — behavioral tests for black-side scenarios")(
    test("both colors castle queenside in the same game (W O-O-O, B O-O-O)") {
      // Clears b1/c1/d1 and b8/c8/d8 via symmetric development, then both
      // sides castle queenside. Mirrors the Najdorf's W O-O-O + B O-O.
      val moves = List(
        "e4",
        "e5",
        "Nf3",
        "Nc6",
        "d4",
        "d5",
        "Nc3",
        "Nf6",
        "Bg5",
        "Be6",
        "Qd2",
        "Qd7",
        "O-O-O",
        "O-O-O"
      )
      for finalState <- replay(GameState.initial, moves)
      yield assertTrue(
        // White castled queenside: king c1, rook d1
        finalState.board(Position('c', 1)) ==
          Piece(Color.White, PieceType.King),
        finalState.board(Position('d', 1)) ==
          Piece(Color.White, PieceType.Rook),
        // Black castled queenside: king c8, rook d8
        finalState.board(Position('c', 8)) ==
          Piece(Color.Black, PieceType.King),
        finalState.board(Position('d', 8)) ==
          Piece(Color.Black, PieceType.Rook),
        // All castling rights revoked
        !finalState.castlingRights.whiteKingSide,
        !finalState.castlingRights.whiteQueenSide,
        !finalState.castlingRights.blackKingSide,
        !finalState.castlingRights.blackQueenSide,
        finalState.status == GameStatus.Playing
      )
    },
    test("black captures en passant and the captured pawn is removed") {
      // Setup: black pawn reaches rank 4 (c4). White double-pushes b2-b4 next
      // to it, setting ep target b3. Black plays cxb3 en passant.
      val moves =
        List("Nc3", "c5", "Nd5", "c4", "b4", "cxb3")
      for finalState <- replay(GameState.initial, moves)
      yield assertTrue(
        // Black pawn arrived on the ep-target square
        finalState.board(Position('b', 3)) ==
          Piece(Color.Black, PieceType.Pawn),
        // The white pawn that double-pushed (b4) is removed
        finalState.board.get(Position('b', 4)).isEmpty,
        // The black pawn's origin (c4) is empty
        finalState.board.get(Position('c', 4)).isEmpty,
        // ep target cleared on the capturing move
        finalState.enPassantTarget.isEmpty,
        // Capture resets halfmove clock
        finalState.halfmoveClock == 0,
        finalState.activeColor == Color.White
      )
    },
    test(
      "black pawn promotes by capture; state consistent through follow-up moves"
    ) {
      // Start: black pawn on a2 about to promote by capturing the white
      // knight on b1. Promotion to queen gives check via rank 1, so white
      // must move the king off rank 1. White also has kingside castling
      // right (the rook on h1) — once the queen captures h1 that right is
      // revoked.
      val startFen = "4k3/8/8/8/8/8/p7/1N2K2R b K - 0 1"
      val moves = List("axb1=Q+", "Ke2", "Qxh1")
      for
        start <- FenParserRegex.parse(startFen)
        after <- replay(start, moves)
      yield assertTrue(
        // Black queen on h1 after capturing the white rook there
        after.board(Position('h', 1)) ==
          Piece(Color.Black, PieceType.Queen),
        // White knight (b1) was captured on promotion
        after.board.get(Position('b', 1)).isEmpty,
        // White's K castling right revoked by the rook's capture on h1
        !after.castlingRights.whiteKingSide,
        !after.castlingRights.whiteQueenSide,
        !after.castlingRights.blackKingSide,
        !after.castlingRights.blackQueenSide,
        // White king escaped check on e2
        after.board(Position('e', 2)) ==
          Piece(Color.White, PieceType.King),
        after.activeColor == Color.White
      )
    },
    test("fool's mate: black checkmates white on move 2") {
      // 1.f3 e5 2.g4?? Qh4# — black queen delivers mate via h4-g3-f2-e1
      // diagonal. Mirrors Scholar's mate (white mates black) for color
      // symmetry.
      val moves = List("f3", "e5", "g4", "Qh4#")
      for finalState <- replay(GameState.initial, moves)
      yield assertTrue(
        finalState.status == GameStatus.Checkmate(Color.Black),
        finalState.inCheck,
        finalState.activeColor == Color.White,
        finalState.board(Position('h', 4)) ==
          Piece(Color.Black, PieceType.Queen)
      )
    }
  )

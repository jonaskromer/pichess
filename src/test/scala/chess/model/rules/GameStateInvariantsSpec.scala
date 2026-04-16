package chess.model.rules

import chess.codec.{FenParserRegex, FenSerializer}
import chess.controller.MoveParser
import chess.model.GameError
import chess.model.board.{GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

/** Per-ply invariant sweep over curated multi-move sequences.
  *
  * For every intermediate [[GameState]] reached by replaying a SAN sequence
  * from the initial position, this spec asserts:
  *
  *   1. '''FEN round-trip''': `FenParserRegex.parse(FenSerializer.serialize(s))`
  *      agrees with `s` on every field except `status` (which FEN does not
  *      encode).
  *   2. '''positionKey determinism''': two consecutive calls to
  *      `FenSerializer.positionKey` on the same state return the same string.
  *
  * The sequences are chosen to exercise the four position-identity fields
  * (board, active color, castling rights, en passant target) — in particular
  * their interactions, which unit tests largely miss:
  *
  *   - kingside castling for both colors in the same game
  *   - opposite-side castling (white O-O-O, black O-O)
  *   - en passant target set, captured, cleared in sequence
  *   - game ending in checkmate
  *
  * This spec serves two purposes: it catches regressions in FEN encoding or
  * game-state mechanics, and it establishes the test corpus against which a
  * future Zobrist hash implementation can be verified for equivalence.
  */
object GameStateInvariantsSpec extends ZIOSpecDefault:

  /** Field-by-field equality ignoring `status`. FEN does not encode status, so
    * a reparsed state always has `status = Playing` — comparing the other
    * fields is the strongest round-trip assertion we can make.
    */
  private def sameExceptStatus(a: GameState, b: GameState): Boolean =
    a.board == b.board &&
      a.activeColor == b.activeColor &&
      a.enPassantTarget == b.enPassantTarget &&
      a.castlingRights == b.castlingRights &&
      a.halfmoveClock == b.halfmoveClock &&
      a.fullmoveNumber == b.fullmoveNumber &&
      a.inCheck == b.inCheck

  /** Replay a SAN move sequence from [[GameState.initial]] and check invariants
    * at every intermediate state. Fails with a descriptive error if any
    * invariant breaks, naming the move that caused the break.
    */
  private def walk(
      sanMoves: List[String]
  ): IO[GameError, List[(String, GameState)]] =
    ZIO
      .foldLeft(sanMoves)(
        (GameState.initial, List.empty[(String, GameState)])
      ) { case ((state, acc), san) =>
        for
          move <- MoveParser.parse(san, state)
          next <- Game.applyMove(state, move)
          fen = FenSerializer.serialize(next)
          reparsed <- FenParserRegex.parse(fen)
          _ <- ZIO.unless(sameExceptStatus(reparsed, next))(
            ZIO.fail(
              GameError.ParseError(
                s"FEN round-trip broken after '$san': " +
                  s"expected board=${next.board} ep=${next.enPassantTarget} " +
                  s"castling=${next.castlingRights}, got board=${reparsed.board} " +
                  s"ep=${reparsed.enPassantTarget} castling=${reparsed.castlingRights}"
              )
            )
          )
          k1 = FenSerializer.positionKey(next)
          k2 = FenSerializer.positionKey(next)
          _ <- ZIO.unless(k1 == k2)(
            ZIO.fail(
              GameError.ParseError(
                s"positionKey not deterministic after '$san'"
              )
            )
          )
        yield (next, (san, next) :: acc)
      }
      .map(_._2.reverse)

  def spec = suite("Game state invariants across curated sequences")(
    test("Ruy Lopez Closed: both colors castle kingside, FENs round-trip") {
      val moves = List(
        "e4",
        "e5",
        "Nf3",
        "Nc6",
        "Bb5",
        "a6",
        "Ba4",
        "Nf6",
        "O-O",
        "Be7",
        "Re1",
        "b5",
        "Bb3",
        "d6",
        "c3",
        "O-O"
      )
      for history <- walk(moves)
      yield
        val finalState = history.last._2
        assertTrue(
          history.length == moves.length,
          // After both kings castle, all castling rights are revoked
          !finalState.castlingRights.whiteKingSide,
          !finalState.castlingRights.whiteQueenSide,
          !finalState.castlingRights.blackKingSide,
          !finalState.castlingRights.blackQueenSide,
          // White castled (king on g1) then played Re1, so rook is on e1
          finalState.board(Position('g', 1)) ==
            Piece(Color.White, PieceType.King),
          finalState.board(Position('e', 1)) ==
            Piece(Color.White, PieceType.Rook),
          // Black just castled kingside on the final move: king on g8, rook on f8
          finalState.board(Position('g', 8)) ==
            Piece(Color.Black, PieceType.King),
          finalState.board(Position('f', 8)) ==
            Piece(Color.Black, PieceType.Rook),
          finalState.activeColor == Color.White,
          finalState.status == GameStatus.Playing
        )
    },
    test("Najdorf English Attack: opposite-side castling (W O-O-O, B O-O)") {
      val moves = List(
        "e4",
        "c5",
        "Nf3",
        "d6",
        "d4",
        "cxd4",
        "Nxd4",
        "Nf6",
        "Nc3",
        "a6",
        "Be3",
        "e5",
        "Nb3",
        "Be6",
        "f3",
        "b5",
        "Qd2",
        "Nbd7",
        "O-O-O",
        "Be7",
        "g4",
        "O-O"
      )
      for history <- walk(moves)
      yield
        val finalState = history.last._2
        assertTrue(
          history.length == moves.length,
          // White castled queenside: king on c1, rook on d1
          finalState.board(Position('c', 1)) ==
            Piece(Color.White, PieceType.King),
          finalState.board(Position('d', 1)) ==
            Piece(Color.White, PieceType.Rook),
          // Black castled kingside: king on g8, rook on f8
          finalState.board(Position('g', 8)) ==
            Piece(Color.Black, PieceType.King),
          finalState.board(Position('f', 8)) ==
            Piece(Color.Black, PieceType.Rook),
          // All castling rights revoked
          !finalState.castlingRights.whiteKingSide,
          !finalState.castlingRights.whiteQueenSide,
          !finalState.castlingRights.blackKingSide,
          !finalState.castlingRights.blackQueenSide,
          finalState.status == GameStatus.Playing
        )
    },
    test("en passant capture: ep target set, consumed, then cleared") {
      val moves = List("e4", "Nf6", "e5", "d5", "exd6")
      for history <- walk(moves)
      yield
        val afterE4 = history(0)._2
        val afterNf6 = history(1)._2
        val afterE5 = history(2)._2
        val afterD5 = history(3)._2
        val afterExd6 = history(4)._2
        assertTrue(
          afterE4.enPassantTarget.contains(Position('e', 3)),
          afterNf6.enPassantTarget.isEmpty,
          afterE5.enPassantTarget.isEmpty,
          afterD5.enPassantTarget.contains(Position('d', 6)),
          afterExd6.enPassantTarget.isEmpty,
          // The ep-captured pawn (which was on d5) is gone
          afterExd6.board.get(Position('d', 5)).isEmpty,
          // White pawn now sits on d6 (the ep target square)
          afterExd6.board(Position('d', 6)) ==
            Piece(Color.White, PieceType.Pawn),
          // Halfmove clock reset by the capture
          afterExd6.halfmoveClock == 0
        )
    },
    test("Scholar's mate: game reaches checkmate, status set correctly") {
      val moves = List("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#")
      for history <- walk(moves)
      yield
        val finalState = history.last._2
        assertTrue(
          history.length == moves.length,
          finalState.status == GameStatus.Checkmate(Color.White),
          finalState.inCheck,
          finalState.activeColor == Color.Black,
          finalState.board(Position('f', 7)) ==
            Piece(Color.White, PieceType.Queen)
        )
    }
  )

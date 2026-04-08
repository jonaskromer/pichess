package chess.codec

import chess.model.board.{CastlingRights, GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object FenSerializerSpec extends ZIOSpecDefault:

  def spec = suite("FenSerializer")(
    test("serializes the standard initial position") {
      assertTrue(
        FenSerializer.serialize(GameState.initial) ==
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      )
    },
    test("emits 'b' for an active black side") {
      val state = GameState.initial.copy(activeColor = Color.Black)
      assertTrue(FenSerializer.serialize(state).contains(" b "))
    },
    test("encodes a board with no castling rights as '-'") {
      val state = GameState(
        board = Map(
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King)
        ),
        activeColor = Color.White,
        castlingRights = CastlingRights(false, false, false, false)
      )
      assertTrue(
        FenSerializer.serialize(state) == "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
      )
    },
    test("encodes en passant target square") {
      val state = GameState(
        board = Map(
          Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King)
        ),
        activeColor = Color.Black,
        enPassantTarget = Some(Position('e', 3)),
        castlingRights = CastlingRights(false, false, false, false)
      )
      val fen = FenSerializer.serialize(state)
      assertTrue(fen.contains(" e3 "))
    },
    test("emits each individual castling right correctly") {
      val state = GameState(
        board = Map(
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King)
        ),
        activeColor = Color.White,
        castlingRights = CastlingRights(
          whiteKingSide = true,
          whiteQueenSide = true,
          blackKingSide = true,
          blackQueenSide = true
        )
      )
      assertTrue(FenSerializer.serialize(state).contains(" KQkq "))
    },
    test("emits a single piece on an otherwise empty rank correctly") {
      val state = GameState(
        board = Map(
          Position('d', 4) -> Piece(Color.White, PieceType.Knight),
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King)
        ),
        activeColor = Color.White,
        castlingRights = CastlingRights(false, false, false, false)
      )
      assertTrue(
        FenSerializer.serialize(state) ==
          "4k3/8/8/8/3N4/8/8/4K3 w - - 0 1"
      )
    },
    test("encodes every piece type with the correct letter") {
      val state = GameState(
        board = Map(
          Position('a', 1) -> Piece(Color.White, PieceType.Rook),
          Position('b', 1) -> Piece(Color.White, PieceType.Knight),
          Position('c', 1) -> Piece(Color.White, PieceType.Bishop),
          Position('d', 1) -> Piece(Color.White, PieceType.Queen),
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('a', 2) -> Piece(Color.White, PieceType.Pawn),
          Position('a', 8) -> Piece(Color.Black, PieceType.Rook),
          Position('b', 8) -> Piece(Color.Black, PieceType.Knight),
          Position('c', 8) -> Piece(Color.Black, PieceType.Bishop),
          Position('d', 8) -> Piece(Color.Black, PieceType.Queen),
          Position('e', 8) -> Piece(Color.Black, PieceType.King),
          Position('a', 7) -> Piece(Color.Black, PieceType.Pawn)
        ),
        activeColor = Color.White,
        castlingRights = CastlingRights(false, false, false, false)
      )
      val fen = FenSerializer.serialize(state)
      assertTrue(
        fen.startsWith("rnbqk3/p7/8/8/8/8/P7/RNBQK3 ")
      )
    }
  )

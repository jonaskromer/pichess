package chess.model.board

import zio.test.*

import chess.model.piece.{Color, Piece, PieceType}

object BoardSpec extends ZIOSpecDefault:

  private val board = Board.initial

  def spec = suite("Board.initial")(
    test("contain exactly 32 pieces") {
      assertTrue(board.size == 32)
    },
    test("have the White King at e1") {
      assertTrue(board(Position('e', 1)) == Piece(Color.White, PieceType.King))
    },
    test("have the Black King at e8") {
      assertTrue(board(Position('e', 8)) == Piece(Color.Black, PieceType.King))
    },
    test("have the White Queen at d1") {
      assertTrue(board(Position('d', 1)) == Piece(Color.White, PieceType.Queen))
    },
    test("have the Black Queen at d8") {
      assertTrue(board(Position('d', 8)) == Piece(Color.Black, PieceType.Queen))
    },
    test("have White Rooks at a1 and h1") {
      assertTrue(
        board(Position('a', 1)) == Piece(Color.White, PieceType.Rook),
        board(Position('h', 1)) == Piece(Color.White, PieceType.Rook)
      )
    },
    test("have Black Rooks at a8 and h8") {
      assertTrue(
        board(Position('a', 8)) == Piece(Color.Black, PieceType.Rook),
        board(Position('h', 8)) == Piece(Color.Black, PieceType.Rook)
      )
    },
    test("have White Knights at b1 and g1") {
      assertTrue(
        board(Position('b', 1)) == Piece(Color.White, PieceType.Knight),
        board(Position('g', 1)) == Piece(Color.White, PieceType.Knight)
      )
    },
    test("have Black Knights at b8 and g8") {
      assertTrue(
        board(Position('b', 8)) == Piece(Color.Black, PieceType.Knight),
        board(Position('g', 8)) == Piece(Color.Black, PieceType.Knight)
      )
    },
    test("have White Bishops at c1 and f1") {
      assertTrue(
        board(Position('c', 1)) == Piece(Color.White, PieceType.Bishop),
        board(Position('f', 1)) == Piece(Color.White, PieceType.Bishop)
      )
    },
    test("have Black Bishops at c8 and f8") {
      assertTrue(
        board(Position('c', 8)) == Piece(Color.Black, PieceType.Bishop),
        board(Position('f', 8)) == Piece(Color.Black, PieceType.Bishop)
      )
    },
    test("have White Pawns on all of row 2") {
      assertTrue(
        ('a' to 'h').forall(col =>
          board(Position(col, 2)) == Piece(Color.White, PieceType.Pawn)
        )
      )
    },
    test("have Black Pawns on all of row 7") {
      assertTrue(
        ('a' to 'h').forall(col =>
          board(Position(col, 7)) == Piece(Color.Black, PieceType.Pawn)
        )
      )
    },
    test("have no pieces on rows 3 through 6") {
      assertTrue(
        (for
          col <- 'a' to 'h'
          row <- 3 to 6
        yield board.get(Position(col, row))).forall(_ == None)
      )
    },
    // Phase 1 bitboard migration — pin down BoardState's edge behaviour.
    suite("BoardState helpers")(
      test("Empty board has isEmpty / size 0 / contains nothing") {
        val empty = BoardState.Empty
        assertTrue(
          empty.isEmpty,
          !empty.nonEmpty,
          empty.size == 0,
          empty.get(Position('e', 4)).isEmpty,
          !empty.contains(Position('e', 4)),
        )
      },
      test("non-empty initial board is nonEmpty") {
        assertTrue(board.nonEmpty, !board.isEmpty)
      },
      test("apply throws NoSuchElementException on an empty square") {
        val empty = BoardState.Empty
        val exit  = scala.util.Try(empty.apply(Position('e', 4)))
        assertTrue(
          exit.isFailure,
          exit.failed.toOption.exists(_.isInstanceOf[NoSuchElementException]),
        )
      },
      test("Bitboard.iterator emits every set bit low-to-high") {
        // Bit 0 + bit 3 + bit 63 — three pieces in LERF order
        val bb = Bitboard.fromLong((1L << 0) | (1L << 3) | (1L << 63))
        assertTrue(bb.iterator.toList == List(0, 3, 63))
      },
      test("Bitboard.iterator on empty bitboard emits nothing") {
        assertTrue(Bitboard.Empty.iterator.isEmpty)
      },
      test("foldLeft visits every (pos, piece) entry, agreeing with toList") {
        // foldLeft is the general form of toList — folding the entries
        // back into a list must reproduce toList exactly (all 32 pieces).
        val entries =
          board.foldLeft(List.empty[(Position, Piece)])((acc, e) => e :: acc).reverse
        assertTrue(
          entries == board.toList,
          entries.size == 32,
        )
      },
      test("movePiece matches (- from + (to -> piece)) for every piece, quiet and capturing") {
        // movePiece is the one-allocation rewrite of `this - from + (to -> piece)`
        // on Game.updatedBoard's hot path. Pin that equivalence across all twelve
        // (color, type) bitboards — which also drives every branch of the field
        // match — for both a quiet move and a capture sitting on `to`.
        val from = Position('d', 4)
        val to   = Position('e', 5)
        val pieces = for
          color <- List(Color.White, Color.Black)
          pt    <- List(
                     PieceType.Pawn, PieceType.Knight, PieceType.Bishop,
                     PieceType.Rook, PieceType.Queen, PieceType.King,
                   )
        yield Piece(color, pt)
        val ok = pieces.forall { piece =>
          val enemy = Piece(piece.color.opposite, PieceType.Queen)
          val quiet = BoardState.Empty + (from -> piece)
          val cap   = quiet + (to -> enemy)
          quiet.movePiece(from, to, piece) == (quiet - from + (to -> piece)) &&
          cap.movePiece(from, to, piece) == (cap - from + (to -> piece))
        }
        assertTrue(ok)
      },
    )
  )

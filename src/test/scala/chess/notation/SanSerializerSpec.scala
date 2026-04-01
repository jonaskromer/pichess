package chess.notation

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SanSerializerSpec extends AnyFlatSpec with Matchers:

  private val initial = GameState.initial

  // ─── Pawn moves ───────────────────────────────────────────────────────────

  "SanSerializer.toSan pawn" should "render a pawn push" in:
    SanSerializer.toSan(
      Move(Position('e', 2), Position('e', 4)),
      initial
    ) shouldBe "e4"

  it should "render a single-square pawn push" in:
    SanSerializer.toSan(
      Move(Position('e', 2), Position('e', 3)),
      initial
    ) shouldBe "e3"

  it should "render a pawn capture with file prefix" in:
    val state = GameState(
      Map(
        Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('e', 4), Position('d', 5)),
      state
    ) shouldBe "exd5"

  it should "render an en passant capture" in:
    val state = GameState(
      Map(
        Position('e', 5) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White,
      enPassantTarget = Some(Position('d', 6))
    )
    SanSerializer.toSan(
      Move(Position('e', 5), Position('d', 6)),
      state
    ) shouldBe "exd6"

  it should "render a promotion" in:
    val state = GameState(
      Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen)),
      state
    ) shouldBe "e8=Q"

  it should "render a capture with promotion" in:
    val state = GameState(
      Map(
        Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
      ),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('e', 7), Position('d', 8), Some(PieceType.Knight)),
      state
    ) shouldBe "exd8=N"

  // ─── Piece moves ──────────────────────────────────────────────────────────

  "SanSerializer.toSan piece" should "render a knight move" in:
    SanSerializer.toSan(
      Move(Position('g', 1), Position('f', 3)),
      initial
    ) shouldBe "Nf3"

  it should "render a piece capture" in:
    val state = GameState(
      Map(
        Position('d', 1) -> Piece(Color.White, PieceType.Queen),
        Position('g', 4) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('d', 1), Position('g', 4)),
      state
    ) shouldBe "Qxg4"

  it should "render a king move" in:
    val state = GameState(
      Map(Position('e', 1) -> Piece(Color.White, PieceType.King)),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('e', 1), Position('f', 2)),
      state
    ) shouldBe "Kf2"

  // ─── Disambiguation ───────────────────────────────────────────────────────

  "SanSerializer.toSan disambiguation" should "add file when two pieces share a rank" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('f', 1) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('a', 1), Position('e', 1)),
      state
    ) shouldBe "Rae1"

  it should "add rank when two pieces share a file" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('a', 5) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('a', 1), Position('a', 3)),
      state
    ) shouldBe "R1a3"

  it should "add file and rank when both are needed" in:
    // Three queens: d4, d8, h4 — move d4→d6
    // d8 shares file (d) and can reach d6; h4 shares rank (4) but cannot reach d6
    // So use queens where both ambiguities arise:
    // Queens at d4, d1, a4. Move d4→a1.
    // d1 can reach a1 (horizontal), a4 can reach a1 (vertical).
    // d1 shares file d with d4; a4 shares rank 4 with d4 — need full disambiguation.
    val state = GameState(
      Map(
        Position('d', 4) -> Piece(Color.White, PieceType.Queen),
        Position('d', 1) -> Piece(Color.White, PieceType.Queen),
        Position('a', 4) -> Piece(Color.White, PieceType.Queen)
      ),
      Color.White
    )
    SanSerializer.toSan(
      Move(Position('d', 4), Position('a', 1)),
      state
    ) shouldBe "Qd4a1"

  it should "not disambiguate when only one piece can reach the destination" in:
    SanSerializer.toSan(
      Move(Position('g', 1), Position('f', 3)),
      initial
    ) shouldBe "Nf3"

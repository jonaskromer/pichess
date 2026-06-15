package chess.model.board

import zio.test.*

import chess.model.piece.PieceType

/** Unit tests for [[MoveInt]] — encoding round-trips and the
  * ordering-pack contract.
  *
  * MoveInt is used by the bot engine's search hot loop, so its
  * correctness is critical. The encode/decode round-trip and the
  * ordering pack (used by `java.util.Arrays.sort` for move
  * ordering) need to hold for every legal combination of (from,
  * to, promotion).
  */
object MoveIntSpec extends ZIOSpecDefault:

  def spec = suite("MoveInt")(
    suite("encode + decode round-trip")(
      test("simple non-promotion move round-trips") {
        val original = Move(Position('e', 2), Position('e', 4), None)
        val encoded  = MoveInt.encodeMove(original)
        val decoded  = MoveInt.decode(encoded)
        assertTrue(decoded == original)
      },
      test("queen promotion round-trips") {
        val original = Move(Position('a', 7), Position('a', 8), Some(PieceType.Queen))
        val encoded  = MoveInt.encodeMove(original)
        val decoded  = MoveInt.decode(encoded)
        assertTrue(decoded == original)
      },
      test("knight promotion round-trips") {
        val original = Move(Position('h', 2), Position('h', 1), Some(PieceType.Knight))
        val encoded  = MoveInt.encodeMove(original)
        val decoded  = MoveInt.decode(encoded)
        assertTrue(decoded == original)
      },
      test("rook promotion round-trips") {
        val original = Move(Position('b', 7), Position('b', 8), Some(PieceType.Rook))
        val encoded  = MoveInt.encodeMove(original)
        val decoded  = MoveInt.decode(encoded)
        assertTrue(decoded == original)
      },
      test("bishop promotion round-trips") {
        val original = Move(Position('c', 7), Position('c', 8), Some(PieceType.Bishop))
        val encoded  = MoveInt.encodeMove(original)
        val decoded  = MoveInt.decode(encoded)
        assertTrue(decoded == original)
      },
      test("decode interns — equal bits yield the SAME instance (flyweight, no per-call alloc)") {
        val original = Move(Position('a', 7), Position('a', 8), Some(PieceType.Queen))
        val encoded  = MoveInt.encodeMove(original)
        val a        = MoveInt.decode(encoded)
        val b        = MoveInt.decode(encoded)
        // Interned: reference-identical across calls (the per-decode Move
        // allocation we removed), and still value-correct.
        assertTrue(a eq b, a == original)
      },
    ),
    suite("encodePromotion + decodePromotion")(
      test("None encodes to NoPromotion") {
        assertTrue(MoveInt.encodePromotion(None) == MoveInt.NoPromotion)
      },
      test("Queen / Rook / Bishop / Knight each map to a distinct constant") {
        val values = List(
          MoveInt.encodePromotion(Some(PieceType.Queen)),
          MoveInt.encodePromotion(Some(PieceType.Rook)),
          MoveInt.encodePromotion(Some(PieceType.Bishop)),
          MoveInt.encodePromotion(Some(PieceType.Knight)),
        )
        assertTrue(values.distinct.size == 4)
      },
      test("non-promotable piece types collapse to NoPromotion") {
        // King and Pawn shouldn't appear in `Move.promotion` but if
        // a caller hands them in, we treat the move as un-promoted
        // rather than throw.
        assertTrue(
          MoveInt.encodePromotion(Some(PieceType.King)) == MoveInt.NoPromotion,
          MoveInt.encodePromotion(Some(PieceType.Pawn)) == MoveInt.NoPromotion,
        )
      },
      test("decodePromotion is the inverse on valid constants") {
        assertTrue(
          MoveInt.decodePromotion(MoveInt.NoPromotion) == None,
          MoveInt.decodePromotion(MoveInt.PromoQueen)  == Some(PieceType.Queen),
          MoveInt.decodePromotion(MoveInt.PromoRook)   == Some(PieceType.Rook),
          MoveInt.decodePromotion(MoveInt.PromoBishop) == Some(PieceType.Bishop),
          MoveInt.decodePromotion(MoveInt.PromoKnight) == Some(PieceType.Knight),
        )
      },
      test("decodePromotion of an out-of-range constant returns None") {
        // Defensive — encoding is 3 bits but only 0..4 are used.
        // Anything else (e.g. a corrupted entry) should collapse to
        // None rather than throw.
        assertTrue(MoveInt.decodePromotion(7) == None)
      },
      test("decode of a move with an out-of-range promotion bit falls back to None") {
        // Hand-build an Int with promo = 7 (binary 111) — outside
        // the 0..4 range. The decoder's catch-all should map that
        // to `None` rather than throwing.
        val rogue = MoveInt.encode(fromIdx = 12, toIdx = 28, promo = 7)
        assertTrue(MoveInt.decode(rogue).promotion == None)
      },
    ),
    suite("pack / fromPacked")(
      test("higher score packs to a larger Long") {
        val low  = MoveInt.pack(score = 0,         insertionIdx = 0, move = 0)
        val high = MoveInt.pack(score = 1_000_000, insertionIdx = 0, move = 0)
        assertTrue(high > low)
      },
      test("ties on score break by ascending insertion index — lower idx → higher Long → iterated first under reverse-sort") {
        val first  = MoveInt.pack(score = 100, insertionIdx = 0,  move = 0)
        val later  = MoveInt.pack(score = 100, insertionIdx = 10, move = 0)
        assertTrue(first > later)
      },
      test("fromPacked recovers the move bits") {
        val move = MoveInt.encode(fromIdx = 12, toIdx = 28, promo = MoveInt.NoPromotion)
        val packed = MoveInt.pack(score = 500, insertionIdx = 3, move = move)
        assertTrue(MoveInt.fromPacked(packed) == move)
      },
    ),
    suite("encode / decode bit-layout")(
      test("fromIdx and toIdx extract independently") {
        val enc = MoveInt.encode(fromIdx = 7, toIdx = 56, promo = 0)
        assertTrue(
          MoveInt.fromIdx(enc) == 7,
          MoveInt.toIdx(enc) == 56,
          MoveInt.promo(enc) == 0,
        )
      },
      test("promotion encoding occupies its own bits — doesn't collide with from/to") {
        val withPromo    = MoveInt.encode(fromIdx = 0, toIdx = 0, promo = MoveInt.PromoQueen)
        val withoutPromo = MoveInt.encode(fromIdx = 0, toIdx = 0, promo = MoveInt.NoPromotion)
        assertTrue(withPromo != withoutPromo)
      },
    ),
  )

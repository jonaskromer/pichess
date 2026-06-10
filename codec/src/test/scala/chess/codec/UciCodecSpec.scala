package chess.codec

import zio.test.*

import chess.model.board.{Move, Position}
import chess.model.piece.PieceType

/** Pins the UCI ↔ [[Move]] round-trip behaviour. UCI is THE wire
  * format used by Lichess (and every other engine on the planet) so
  * incorrect parsing here breaks every game from move 1.
  *
  * Lives in the `codec` module alongside [[UciCodec]] itself — the
  * `bot-lichess` adapter only re-exports it via a `val` alias, so the
  * behaviour is pinned here where the implementation lives.
  */
object UciCodecSpec extends ZIOSpecDefault:

  def spec = suite("UciCodec")(
    suite("parse")(
      test("4-char move with no promotion") {
        assertTrue(
          UciCodec.parse("e2e4") ==
            Right(Move(Position('e', 2), Position('e', 4), None))
        )
      },
      test("5-char move with queen promotion") {
        assertTrue(
          UciCodec.parse("e7e8q") ==
            Right(Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen)))
        )
      },
      test("all four promotion pieces parse to their PieceType") {
        assertTrue(
          UciCodec.parse("a7a8q").map(_.promotion) == Right(Some(PieceType.Queen)),
          UciCodec.parse("a7a8r").map(_.promotion) == Right(Some(PieceType.Rook)),
          UciCodec.parse("a7a8b").map(_.promotion) == Right(Some(PieceType.Bishop)),
          UciCodec.parse("a7a8n").map(_.promotion) == Right(Some(PieceType.Knight)),
        )
      },
      test("castling round-trips as a two-square king move") {
        // e1g1 is white short castle; we don't tag it as special, the
        // server-side rules engine recognises the geometry on apply.
        assertTrue(
          UciCodec.parse("e1g1") ==
            Right(Move(Position('e', 1), Position('g', 1), None))
        )
      },
      test("rejects wrong length") {
        assertTrue(
          UciCodec.parse("").isLeft,
          UciCodec.parse("e2").isLeft,
          UciCodec.parse("e2e4qq").isLeft,
        )
      },
      test("rejects out-of-range squares") {
        assertTrue(
          UciCodec.parse("e9e4").isLeft,
          UciCodec.parse("z2e4").isLeft,
          UciCodec.parse("e0e1").isLeft,
        )
      },
      test("rejects unknown promotion letters") {
        assertTrue(
          UciCodec.parse("e7e8k").isLeft,
          UciCodec.parse("e7e8x").isLeft,
        )
      },
    ),
    suite("serialize")(
      test("plain move drops the promotion suffix") {
        assertTrue(
          UciCodec.serialize(Move(Position('e', 2), Position('e', 4))) == "e2e4"
        )
      },
      test("promotion suffix matches the piece type letter") {
        assertTrue(
          UciCodec.serialize(Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))) == "e7e8q",
          UciCodec.serialize(Move(Position('e', 7), Position('e', 8), Some(PieceType.Rook))) == "e7e8r",
          UciCodec.serialize(Move(Position('e', 7), Position('e', 8), Some(PieceType.Bishop))) == "e7e8b",
          UciCodec.serialize(Move(Position('e', 7), Position('e', 8), Some(PieceType.Knight))) == "e7e8n",
        )
      },
      test("never tags a King or Pawn promotion (those aren't legal)") {
        // Defensive — Move.promotion accepts any PieceType, but only
        // Q/R/B/N are legal promotion targets. Serialise the rest as
        // a plain move so we don't emit garbage UCI.
        assertTrue(
          UciCodec.serialize(Move(Position('e', 7), Position('e', 8), Some(PieceType.King))) == "e7e8",
          UciCodec.serialize(Move(Position('e', 7), Position('e', 8), Some(PieceType.Pawn))) == "e7e8",
        )
      },
    ),
    suite("round-trip")(
      test("every parsed UCI re-serialises to its input") {
        val samples = List("e2e4", "g1f3", "a7a8q", "e1g1", "e7e8n", "h2h4")
        assertTrue(
          samples.forall(u => UciCodec.parse(u).map(UciCodec.serialize) == Right(u))
        )
      },
    ),
  )

package chess.codec

import java.time.Instant

import zio.*
import zio.test.*

import chess.model.board.{DrawReason, GameStatus}
import chess.model.piece.Color

object PgnSerializerSpec extends ZIOSpecDefault:

  def spec = suite("PgnSerializer")(
    test("serializes an empty game as header + result marker") {
      for pgn <- PgnSerializer.serialize(Nil, GameStatus.Playing)
      yield assertTrue(
        pgn.contains("[Event"),
        pgn.contains("[Result \"*\"]"),
        pgn.endsWith("*")
      )
    },
    test("serializes moves with move numbers") {
      val log = List(
        (Color.White, "e4"),
        (Color.Black, "e5"),
        (Color.White, "Nf3")
      )
      for pgn <- PgnSerializer.serialize(log, GameStatus.Playing)
      yield assertTrue(
        pgn.contains("1. e4 e5"),
        pgn.contains("2. Nf3"),
        pgn.endsWith("*")
      )
    },
    test("serializes checkmate result as 1-0 for white win") {
      val log = List(
        (Color.White, "e4"),
        (Color.Black, "f6"),
        (Color.White, "d4"),
        (Color.Black, "g5"),
        (Color.White, "Qh5#")
      )
      for pgn <- PgnSerializer.serialize(log, GameStatus.Checkmate(Color.White))
      yield assertTrue(
        pgn.contains("[Result \"1-0\"]"),
        pgn.endsWith("1-0")
      )
    },
    test("serializes checkmate result as 0-1 for black win") {
      val log = List(
        (Color.White, "f3"),
        (Color.Black, "e5"),
        (Color.White, "g4"),
        (Color.Black, "Qh4#")
      )
      for pgn <- PgnSerializer.serialize(log, GameStatus.Checkmate(Color.Black))
      yield assertTrue(
        pgn.contains("[Result \"0-1\"]"),
        pgn.endsWith("0-1")
      )
    },
    test("serializes draw result as 1/2-1/2") {
      for pgn <- PgnSerializer.serialize(
          Nil,
          GameStatus.Draw(DrawReason.FiftyMoveRule)
        )
      yield assertTrue(
        pgn.contains("[Result \"1/2-1/2\"]"),
        pgn.endsWith("1/2-1/2")
      )
    },
    test("serializes white-resignation result as 0-1 (white loses)") {
      // Resignation collapses onto the same result token as a checkmate
      // loss for the resigning side — PGN itself doesn't distinguish.
      for pgn <- PgnSerializer.serialize(
          List((Color.White, "e4"), (Color.Black, "e5")),
          GameStatus.Resignation(Color.Black)
        )
      yield assertTrue(
        pgn.contains("[Result \"0-1\"]"),
        pgn.endsWith("0-1")
      )
    },
    test("serializes black-resignation result as 1-0 (black loses)") {
      for pgn <- PgnSerializer.serialize(
          Nil,
          GameStatus.Resignation(Color.White)
        )
      yield assertTrue(
        pgn.contains("[Result \"1-0\"]"),
        pgn.endsWith("1-0")
      )
    },
    test("includes all seven PGN tag roster headers") {
      for pgn <- PgnSerializer.serialize(Nil, GameStatus.Playing)
      yield assertTrue(
        pgn.contains("[Event \"πChess Game\"]"),
        pgn.contains("[Site \"Local\"]"),
        pgn.contains("[Date"),
        pgn.contains("[Round \"1\"]"),
        pgn.contains("[White \"Player 1\"]"),
        pgn.contains("[Black \"Player 2\"]"),
        pgn.contains("[Result")
      )
    },
    test("Date header is read from the clock (deterministic under TestClock)") {
      // Set the clock to a known instant; assert the Date header reflects it.
      // Proves that the function is pure given its clock context — no hidden
      // LocalDate.now() sneaking in.
      val fixed = Instant.parse("2026-04-16T12:00:00Z")
      for
        _ <- TestClock.setTime(fixed)
        pgn <- PgnSerializer.serialize(Nil, GameStatus.Playing)
      yield assertTrue(pgn.contains("""[Date "2026.04.16"]"""))
    },
    test("emits NAG + clock annotations; black after an annotated move gets N...") {
      val moves = List(
        PgnMove(Color.White, "e4", clockMs = Some(90000)),
        PgnMove(
          Color.Black,
          "c5",
          nag = Some(Nag.Dubious),
          clockMs = Some(88000),
          emtMs = Some(2000)
        )
      )
      for pgn <- PgnSerializer.serializeAnnotated(moves, GameStatus.Playing)
      yield assertTrue(
        pgn.contains("1. e4 {[%clk 0:01:30]}"),
        pgn.contains("1... c5 $6 {[%emt 2] [%clk 0:01:28]}")
      )
    },
    test("a leading black move takes no number prefix (i==0 branch)") {
      for pgn <- PgnSerializer.serializeAnnotated(
          List(PgnMove(Color.Black, "e5")),
          GameStatus.Playing
        )
      yield assertTrue(
        pgn.endsWith("\n\ne5 *"),
        !pgn.contains("1... e5"),
        !pgn.contains("1. e5")
      )
    },
    test("serializeWithResult takes an explicit result token") {
      for pgn <- PgnSerializer.serializeWithResult(
          List(PgnMove(Color.White, "e4")),
          "1/2-1/2",
          List("ECO" -> "B20")
        )
      yield assertTrue(
        pgn.contains("[Result \"1/2-1/2\"]"),
        pgn.contains("[ECO \"B20\"]"),
        pgn.endsWith("1/2-1/2")
      )
    },
    test("serializeWithResult defaults extraHeaders to none (2-arg form)") {
      // Every caller (e.g. the game archive) passes headers explicitly, so the
      // defaulted third parameter (`extraHeaders = Nil`) is only exercised here
      // — the 2-arg form emits the default roster with no overlay.
      for pgn <- PgnSerializer.serializeWithResult(
          List(PgnMove(Color.White, "e4")),
          "1-0"
        )
      yield assertTrue(
        pgn.contains("[Result \"1-0\"]"),
        pgn.contains("[Site \"Local\"]"),
        pgn.contains("1. e4"),
        pgn.endsWith("1-0")
      )
    },
    test("overlays extra headers, overriding defaults and appending new tags") {
      for pgn <- PgnSerializer.serializeAnnotated(
          List(PgnMove(Color.White, "e4")),
          GameStatus.Playing,
          extraHeaders = List(
            "White"   -> "piChess",
            "ECO"     -> "B20",
            "Opening" -> "Sicilian Defense"
          )
        )
      yield assertTrue(
        pgn.contains("""[White "piChess"]"""),
        !pgn.contains("""[White "Player 1"]"""),
        pgn.contains("""[ECO "B20"]"""),
        pgn.contains("""[Opening "Sicilian Defense"]""")
      )
    }
  )

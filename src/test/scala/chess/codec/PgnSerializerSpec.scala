package chess.codec

import chess.model.board.{DrawReason, GameStatus}
import chess.model.piece.Color
import zio.*
import zio.test.*

import java.time.Instant

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
    }
  )

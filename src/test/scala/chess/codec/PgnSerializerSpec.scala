package chess.codec

import chess.model.board.GameStatus
import chess.model.piece.Color
import zio.test.*

object PgnSerializerSpec extends ZIOSpecDefault:

  def spec = suite("PgnSerializer")(
    test("serializes an empty game as header + result marker") {
      val pgn = PgnSerializer.serialize(Nil, GameStatus.Playing)
      assertTrue(
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
      val pgn = PgnSerializer.serialize(log, GameStatus.Playing)
      assertTrue(
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
      val pgn =
        PgnSerializer.serialize(log, GameStatus.Checkmate(Color.White))
      assertTrue(
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
      val pgn =
        PgnSerializer.serialize(log, GameStatus.Checkmate(Color.Black))
      assertTrue(
        pgn.contains("[Result \"0-1\"]"),
        pgn.endsWith("0-1")
      )
    },
    test("serializes draw result as 1/2-1/2") {
      val pgn =
        PgnSerializer.serialize(Nil, GameStatus.Draw("50-move rule"))
      assertTrue(
        pgn.contains("[Result \"1/2-1/2\"]"),
        pgn.endsWith("1/2-1/2")
      )
    },
    test("includes standard PGN headers") {
      val pgn = PgnSerializer.serialize(Nil, GameStatus.Playing)
      assertTrue(
        pgn.contains("[Event"),
        pgn.contains("[Site"),
        pgn.contains("[Date"),
        pgn.contains("[White"),
        pgn.contains("[Black"),
        pgn.contains("[Result")
      )
    }
  )

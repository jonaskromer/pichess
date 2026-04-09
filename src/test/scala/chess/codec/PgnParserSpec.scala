package chess.codec

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.notation.SanSerializer
import zio.test.*

object PgnParserSpec extends ZIOSpecDefault:

  def spec = suite("PgnParser")(
    test("parses a simple opening sequence") {
      val pgn = """[Event "Test"]
                  |[Result "*"]
                  |
                  |1. e4 e5 2. Nf3 Nc6 *""".stripMargin
      for
        result <- PgnParser.parse(pgn)
        sanLog <- SanSerializer
          .deriveMoveLog(result.initialState, result.history.reverse)
      yield assertTrue(
        result.moves.length == 4,
        sanLog.head == (Color.White, "e4"),
        sanLog(1) == (Color.Black, "e5"),
        sanLog(2) == (Color.White, "Nf3"),
        sanLog(3) == (Color.Black, "Nc6"),
        result.state.board(Position('e', 4)) == Piece(
          Color.White,
          PieceType.Pawn
        ),
        result.state.board(Position('f', 3)) == Piece(
          Color.White,
          PieceType.Knight
        )
      )
    },
    test("parses headers into a map") {
      val pgn = """[Event "Casual Game"]
                  |[White "Alice"]
                  |[Black "Bob"]
                  |[Result "*"]
                  |
                  |1. d4 *""".stripMargin
      for result <- PgnParser.parse(pgn)
      yield assertTrue(
        result.headers("Event") == "Casual Game",
        result.headers("White") == "Alice",
        result.headers("Black") == "Bob"
      )
    },
    test("parses PGN without headers (movetext only)") {
      val pgn = "1. e4 e5 *"
      for result <- PgnParser.parse(pgn)
      yield assertTrue(
        result.moves.length == 2,
        result.state.board(Position('e', 4)) == Piece(
          Color.White,
          PieceType.Pawn
        )
      )
    },
    test("ignores result tokens in movetext") {
      val pgn = "1. e4 e5 2. Nf3 1-0"
      for result <- PgnParser.parse(pgn)
      yield assertTrue(result.moves.length == 3)
    },
    test("ignores comments in braces") {
      val pgn = "1. e4 {best move} e5 {solid reply} *"
      for result <- PgnParser.parse(pgn)
      yield assertTrue(result.moves.length == 2)
    },
    test("ignores NAG annotations") {
      val pgn = "1. e4 $1 e5 $2 *"
      for result <- PgnParser.parse(pgn)
      yield assertTrue(result.moves.length == 2)
    },
    test("fails on illegal move in PGN") {
      val pgn = "1. e4 e5 2. e4 *"
      for exit <- PgnParser.parse(pgn).exit
      yield assertTrue(exit.isFailure)
    },
    test("fails on invalid FEN in header") {
      val pgn = """[FEN "not a valid fen"]
                  |
                  |*""".stripMargin
      for exit <- PgnParser.parse(pgn).exit
      yield assertTrue(exit.isFailure)
    },
    test("ignores malformed header lines") {
      val pgn = """[Event "Test"]
                  |[malformed line without quotes]
                  |
                  |1. e4 *""".stripMargin
      for result <- PgnParser.parse(pgn)
      yield assertTrue(
        result.headers.size == 1,
        result.moves.length == 1
      )
    },
    test("round-trips with PgnSerializer") {
      val pgn = "1. e4 e5 2. Nf3 Nc6 *"
      for
        result <- PgnParser.parse(pgn)
        sanLog <- SanSerializer.deriveMoveLog(
          result.initialState,
          result.history.reverse
        )
        serialized = PgnSerializer.serialize(sanLog, result.state.status)
        reparsed <- PgnParser.parse(serialized)
        reparsedSan <- SanSerializer.deriveMoveLog(
          reparsed.initialState,
          reparsed.history.reverse
        )
      yield assertTrue(
        reparsedSan == sanLog,
        reparsed.state == result.state
      )
    },
    test("parses PGN with FEN header for custom start position") {
      val pgn = """[FEN "4k3/8/8/8/4P3/8/8/4K3 b - - 0 1"]
                  |[Result "*"]
                  |
                  |1. Kd7 *""".stripMargin
      for
        result <- PgnParser.parse(pgn)
        sanLog <- SanSerializer
          .deriveMoveLog(result.initialState, result.history.reverse)
      yield assertTrue(
        result.moves.length == 1,
        sanLog.head._1 == Color.Black,
        result.state.activeColor == Color.White
      )
    }
  )

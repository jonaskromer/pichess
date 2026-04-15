package chess.codec

import chess.model.board.{
  CastlingRights,
  DrawReason,
  GameState,
  GameStatus,
  Position
}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object JsonCodecSpec extends ZIOSpecDefault:

  def spec = suite("JsonCodec")(
    suite("JsonSerializer")(
      test("serializes the initial position to valid JSON that round-trips") {
        val json = JsonSerializer.serialize(GameState.initial)
        for state <- JsonParser.parse(json)
        yield assertTrue(state == GameState.initial)
      },
      test("serializes en passant target") {
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
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(json.contains("e3"), parsed == state)
      },
      test("serializes stalemate status") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(false, false, false, false),
          status = GameStatus.Draw(DrawReason.Stalemate)
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(json.contains("Stalemate"), parsed == state)
      },
      test("serializes insufficient material status") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(false, false, false, false),
          status = GameStatus.Draw(DrawReason.InsufficientMaterial)
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(
          json.contains("InsufficientMaterial"),
          parsed == state
        )
      },
      test("round-trips halfmove clock and fullmove number") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.Black,
          castlingRights = CastlingRights(false, false, false, false),
          halfmoveClock = 42,
          fullmoveNumber = 87
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(
          json.contains("42"),
          json.contains("87"),
          parsed == state
        )
      },
      test("defaults halfmove and fullmove when missing from JSON") {
        val json =
          """{"board":{},"activeColor":"White","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":{"Playing":{}}}"""
        for state <- JsonParser.parse(json)
        yield assertTrue(
          state.halfmoveClock == 0,
          state.fullmoveNumber == 1
        )
      },
      test("serializes checkmate status") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(false, false, false, false),
          status = GameStatus.Checkmate(Color.White)
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(json.contains("Checkmate"), parsed == state)
      },
      test("serializes draw status") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(false, false, false, false),
          status = GameStatus.Draw(DrawReason.FiftyMoveRule)
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(json.contains("FiftyMoveRule"), parsed == state)
      },
      test("round-trips threefold repetition draw") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(false, false, false, false),
          status = GameStatus.Draw(DrawReason.ThreefoldRepetition)
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(
          json.contains("ThreefoldRepetition"),
          parsed == state
        )
      },
      test("round-trips fivefold repetition draw") {
        val state = GameState(
          board = Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          activeColor = Color.White,
          castlingRights = CastlingRights(false, false, false, false),
          status = GameStatus.Draw(DrawReason.FivefoldRepetition)
        )
        val json = JsonSerializer.serialize(state)
        for parsed <- JsonParser.parse(json)
        yield assertTrue(
          json.contains("FivefoldRepetition"),
          parsed == state
        )
      }
    ),
    suite("JsonParser")(
      test("parses a hand-written JSON position") {
        val json =
          """{
            "board": {
              "e1": {"color":"White","pieceType":"King"},
              "e8": {"color":"Black","pieceType":"King"},
              "d4": {"color":"White","pieceType":"Knight"}
            },
            "activeColor": "White",
            "castlingRights": {"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},
            "enPassantTarget": null,
            "inCheck": false,
            "status": {"Playing":{}}
          }"""
        for state <- JsonParser.parse(json)
        yield assertTrue(
          state.board.size == 3,
          state.board(Position('d', 4)) == Piece(Color.White, PieceType.Knight),
          state.activeColor == Color.White,
          !state.inCheck,
          state.status == GameStatus.Playing
        )
      },
      test("rejects invalid JSON") {
        for exit <- JsonParser.parse("not json").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects missing board field") {
        val json = """{"activeColor":"white"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid color in piece") {
        val json =
          """{"board":{"e1":"purple king"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid piece type") {
        val json =
          """{"board":{"e1":{"color":"White","pieceType":"Dragon"}},"activeColor":"White","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":{"Playing":{}}}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects piece with no space separator") {
        val json =
          """{"board":{"e1":"whiteking"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects board value that is not a string") {
        val json =
          """{"board":{"e1":true},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects board that is not an object") {
        val json =
          """{"board":"not an object","activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid position key in board") {
        val json =
          """{"board":{"zz":"white king"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid active color") {
        val json =
          """{"board":{},"activeColor":"purple","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects activeColor that is not a string") {
        val json =
          """{"board":{},"activeColor":true,"castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid en passant target") {
        val json =
          """{"board":{},"activeColor":"White","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":"zz","inCheck":false,"status":{"Playing":{}}}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid en passant type") {
        val json =
          """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":true,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid status type") {
        val json =
          """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":true}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects invalid status object") {
        val json =
          """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":{"winner":"white"}}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects top-level non-object") {
        for exit <- JsonParser.parse("\"hello\"").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects unknown draw reason") {
        val json =
          """{"board":{},"activeColor":"White","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":{"Draw":{"reason":"forfeit"}}}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects missing board field (duplicate)") {
        val json = """{"activeColor":"white"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects empty input") {
        for exit <- JsonParser.parse("").exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects malformed castlingRights") {
        val json =
          """{"board":{},"activeColor":"white","castlingRights":"none","enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects non-numeric halfmoveClock") {
        val json =
          """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing","halfmoveClock":"oops","fullmoveNumber":7}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      },
      test("rejects non-numeric fullmoveNumber") {
        val json =
          """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing","halfmoveClock":3,"fullmoveNumber":"oops"}"""
        for exit <- JsonParser.parse(json).exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("FEN vs JSON cross-validation")(
      test("initial position: FEN and JSON produce the same GameState") {
        val json = JsonSerializer.serialize(GameState.initial)
        for
          fenState <- FenParserRegex.parse(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
          )
          jsonState <- JsonParser.parse(json)
        yield assertTrue(jsonState == fenState)
      },
      test("after 1.e4: FEN and JSON produce the same GameState") {
        for
          fenState <- FenParserRegex.parse(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
          )
          json = JsonSerializer.serialize(fenState)
          jsonState <- JsonParser.parse(json)
        yield assertTrue(jsonState == fenState)
      },
      test("black in check: FEN and JSON produce the same GameState") {
        for
          fenState <- FenParserRegex.parse(
            "4k3/4R3/8/8/8/8/8/4K3 b - - 0 1"
          )
          json = JsonSerializer.serialize(fenState)
          jsonState <- JsonParser.parse(json)
        yield assertTrue(jsonState == fenState)
      },
      test("partial castling rights: FEN and JSON produce the same GameState") {
        for
          fenState <- FenParserRegex.parse(
            "r3k2r/8/8/8/8/8/8/R3K2R b Kq - 0 1"
          )
          json = JsonSerializer.serialize(fenState)
          jsonState <- JsonParser.parse(json)
        yield assertTrue(jsonState == fenState)
      }
    )
  )

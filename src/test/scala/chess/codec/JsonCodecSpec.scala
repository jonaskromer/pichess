package chess.codec

import chess.model.board.{CastlingRights, DrawReason, GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object JsonCodecSpec extends ZIOSpecDefault:

  def spec = suite("JsonCodec")(
    suite("JsonSerializer")(
      test("serializes the initial position to valid JSON that round-trips") {
        val json = JsonSerializer.serialize(GameState.initial)
        val parsed = JsonParser.parse(json)
        assertTrue(parsed == Right(GameState.initial))
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
        assertTrue(
          json.contains("e3"),
          JsonParser.parse(json) == Right(state)
        )
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
        assertTrue(
          json.contains("stalemate"),
          JsonParser.parse(json) == Right(state)
        )
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
        assertTrue(
          json.contains("insufficient material"),
          JsonParser.parse(json) == Right(state)
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
        assertTrue(
          json.contains("42"),
          json.contains("87"),
          JsonParser.parse(json) == Right(state)
        )
      },
      test("defaults halfmove and fullmove when missing from JSON") {
        val json = """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        val Right(state) = JsonParser.parse(json): @unchecked
        assertTrue(
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
        assertTrue(
          json.contains("checkmate"),
          JsonParser.parse(json) == Right(state)
        )
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
        assertTrue(
          json.contains("fifty-move rule"),
          JsonParser.parse(json) == Right(state)
        )
      }
    ),
    suite("JsonParser")(
      test("parses a hand-written JSON position") {
        val json = """{"board":{"e1":"white king","e8":"black king","d4":"white knight"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        val Right(state) = JsonParser.parse(json): @unchecked
        assertTrue(
          state.board.size == 3,
          state.board(Position('d', 4)) == Piece(Color.White, PieceType.Knight),
          state.activeColor == Color.White,
          !state.inCheck,
          state.status == GameStatus.Playing
        )
      },
      test("rejects invalid JSON") {
        assertTrue(JsonParser.parse("not json").isLeft)
      },
      test("rejects missing board field") {
        val json = """{"activeColor":"white"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid color in piece") {
        val json = """{"board":{"e1":"purple king"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid piece type") {
        val json = """{"board":{"e1":"white dragon"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects piece with no space separator") {
        val json = """{"board":{"e1":"whiteking"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects board value that is not a string") {
        val json = """{"board":{"e1":true},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects board that is not an object") {
        val json = """{"board":"not an object","activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid position key in board") {
        val json = """{"board":{"zz":"white king"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid active color") {
        val json = """{"board":{},"activeColor":"purple","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects activeColor that is not a string") {
        val json = """{"board":{},"activeColor":true,"castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid en passant target") {
        val json = """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":"zz","inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid en passant type") {
        val json = """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":true,"inCheck":false,"status":"playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid status type") {
        val json = """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":true}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid status object") {
        val json = """{"board":{},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":{"winner":"white"}}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects top-level non-object") {
        assertTrue(JsonParser.parse("\"hello\"").isLeft)
      },
      test("rejects unknown draw reason") {
        val json = """{"board":{"e1":"white king"},"activeColor":"white","castlingRights":{"whiteKingSide":false,"whiteQueenSide":false,"blackKingSide":false,"blackQueenSide":false},"enPassantTarget":null,"inCheck":false,"status":{"draw":"unknown reason"}}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects missing board field") {
        val json = """{"activeColor":"white"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects empty input") {
        assertTrue(JsonParser.parse("").isLeft)
      }
    ),
    suite("FEN vs JSON cross-validation")(
      test("initial position: FEN and JSON produce the same GameState") {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val json = JsonSerializer.serialize(GameState.initial)
        val fenState = FenParserRegex.parse(fen)
        val jsonState = JsonParser.parse(json)
        assertTrue(
          fenState.isRight,
          jsonState.isRight,
          fenState == jsonState
        )
      },
      test("after 1.e4: FEN and JSON produce the same GameState") {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val Right(fenState) = FenParserRegex.parse(fen): @unchecked
        val json = JsonSerializer.serialize(fenState)
        val jsonState = JsonParser.parse(json)
        assertTrue(jsonState == Right(fenState))
      },
      test("black in check: FEN and JSON produce the same GameState") {
        val fen = "4k3/4R3/8/8/8/8/8/4K3 b - - 0 1"
        val Right(fenState) = FenParserRegex.parse(fen): @unchecked
        val json = JsonSerializer.serialize(fenState)
        val jsonState = JsonParser.parse(json)
        assertTrue(jsonState == Right(fenState))
      },
      test("partial castling rights: FEN and JSON produce the same GameState") {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R b Kq - 0 1"
        val Right(fenState) = FenParserRegex.parse(fen): @unchecked
        val json = JsonSerializer.serialize(fenState)
        val jsonState = JsonParser.parse(json)
        assertTrue(jsonState == Right(fenState))
      }
    )
  )

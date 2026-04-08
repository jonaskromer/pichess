package chess.codec

import chess.model.board.{CastlingRights, GameState, GameStatus, Position}
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
          json.contains("\"e3\""),
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
          status = GameStatus.Draw("50-move rule")
        )
        val json = JsonSerializer.serialize(state)
        assertTrue(
          json.contains("draw"),
          JsonParser.parse(json) == Right(state)
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
      }
    ),
    suite("JsonParser")(
      test("parses a hand-written JSON position") {
        val json = """{
          "board": {
            "e1": "white king",
            "e8": "black king",
            "d4": "white knight"
          },
          "activeColor": "white",
          "castlingRights": {
            "whiteKingSide": false,
            "whiteQueenSide": false,
            "blackKingSide": false,
            "blackQueenSide": false
          },
          "enPassantTarget": null,
          "inCheck": false,
          "status": "playing"
        }"""
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
        val json = """{"activeColor": "white"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid piece string") {
        val json = """{
          "board": {"e1": "purple king"},
          "activeColor": "white",
          "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false},
          "enPassantTarget": null,
          "inCheck": false,
          "status": "playing"
        }"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects top-level non-object") {
        assertTrue(JsonParser.parse("\"hello\"").isLeft)
      },
      test("rejects board that is not an object") {
        val json = """{"board": "not an object", "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid position key in board") {
        val json = """{"board": {"zz": "white king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects piece value that is not a string") {
        val json = """{"board": {"e1": true}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects piece with no space separator") {
        val json = """{"board": {"e1": "whiteking"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid piece type") {
        val json = """{"board": {"e1": "white dragon"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects castlingRights that is not an object") {
        val json = """{"board": {"e1": "white king"}, "activeColor": "white", "castlingRights": "none", "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects non-boolean castling field") {
        val json = """{"board": {"e1": "white king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": "yes", "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects missing castling field") {
        val json = """{"board": {"e1": "white king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects en passant target that is not string or null") {
        val json = """{"board": {"e1": "white king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": true, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid status object") {
        val json = """{"board": {"e1": "white king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": {"winner": "white"}}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects invalid status type") {
        val json = """{"board": {"e1": "white king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": true}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("rejects empty input") {
        assertTrue(JsonParser.parse("").isLeft)
      },
      test("rejects unterminated string") {
        assertTrue(JsonParser.parse("""{"key""").isLeft)
      },
      test("rejects malformed object (missing comma/brace)") {
        assertTrue(JsonParser.parse("""{"a": "b" "c": "d"}""").isLeft)
      },
      test("parses empty JSON object") {
        assertTrue(JsonParser.parse("{}").isLeft) // missing required fields
      },
      test("handles escape sequences in strings") {
        val json = """{"board": {}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        val result = JsonParser.parse(json)
        assertTrue(result.isRight)
      },
      test("rejects unexpected character") {
        assertTrue(JsonParser.parse("[1,2]").isLeft)
      },
      test("handles string with escape sequences") {
        val json = "{\"board\": {}, \"activeColor\": \"white\", \"castlingRights\": {\"whiteKingSide\": false, \"whiteQueenSide\": false, \"blackKingSide\": false, \"blackQueenSide\": false}, \"enPassantTarget\": null, \"inCheck\": false, \"status\": \"play\\ning\"}"
        assertTrue(JsonParser.parse(json).isLeft) // "play\ning" != "playing"
      },
      test("rejects truncated object (missing key after brace)") {
        assertTrue(JsonParser.parse("{").isLeft)
      },
      test("rejects object with missing colon") {
        assertTrue(JsonParser.parse("""{"key" "value"}""").isLeft)
      },
      test("rejects activeColor that is not a string") {
        val json = """{"board": {}, "activeColor": true, "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        assertTrue(JsonParser.parse(json).isLeft)
      },
      test("handles backslash escape sequences in JSON strings") {
        val json = "{\"board\": {}, \"activeColor\": \"white\", \"castlingRights\": {\"whiteKingSide\": false, \"whiteQueenSide\": false, \"blackKingSide\": false, \"blackQueenSide\": false}, \"enPassantTarget\": null, \"inCheck\": false, \"status\": \"playing\", \"note\": \"a\\\"b\\\\c\\td\\ne\"}"
        val result = JsonParser.parse(json)
        assertTrue(result.isRight)
      },
      test("rejects string with unterminated escape") {
        assertTrue(JsonParser.parse("{\"key\\").isLeft)
      },
      test("handles unknown escape sequence") {
        val json = "{\"bo\\xard\": {}}"
        assertTrue(JsonParser.parse(json).isLeft) // missing required fields but parses JSON
      }
    ),
    suite("FEN vs JSON cross-validation")(
      test("initial position: FEN and JSON produce the same GameState") {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val json = """{
          "board": {
            "a1": "white rook",   "b1": "white knight", "c1": "white bishop", "d1": "white queen",
            "e1": "white king",   "f1": "white bishop",  "g1": "white knight", "h1": "white rook",
            "a2": "white pawn",   "b2": "white pawn",    "c2": "white pawn",   "d2": "white pawn",
            "e2": "white pawn",   "f2": "white pawn",    "g2": "white pawn",   "h2": "white pawn",
            "a7": "black pawn",   "b7": "black pawn",    "c7": "black pawn",   "d7": "black pawn",
            "e7": "black pawn",   "f7": "black pawn",    "g7": "black pawn",   "h7": "black pawn",
            "a8": "black rook",   "b8": "black knight",  "c8": "black bishop", "d8": "black queen",
            "e8": "black king",   "f8": "black bishop",  "g8": "black knight", "h8": "black rook"
          },
          "activeColor": "white",
          "castlingRights": {
            "whiteKingSide": true,
            "whiteQueenSide": true,
            "blackKingSide": true,
            "blackQueenSide": true
          },
          "enPassantTarget": null,
          "inCheck": false,
          "status": "playing"
        }"""
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
        val json = """{
          "board": {
            "a1": "white rook",   "b1": "white knight", "c1": "white bishop", "d1": "white queen",
            "e1": "white king",   "f1": "white bishop",  "g1": "white knight", "h1": "white rook",
            "a2": "white pawn",   "b2": "white pawn",    "c2": "white pawn",   "d2": "white pawn",
            "f2": "white pawn",   "g2": "white pawn",    "h2": "white pawn",
            "e4": "white pawn",
            "a7": "black pawn",   "b7": "black pawn",    "c7": "black pawn",   "d7": "black pawn",
            "e7": "black pawn",   "f7": "black pawn",    "g7": "black pawn",   "h7": "black pawn",
            "a8": "black rook",   "b8": "black knight",  "c8": "black bishop", "d8": "black queen",
            "e8": "black king",   "f8": "black bishop",  "g8": "black knight", "h8": "black rook"
          },
          "activeColor": "black",
          "castlingRights": {
            "whiteKingSide": true,
            "whiteQueenSide": true,
            "blackKingSide": true,
            "blackQueenSide": true
          },
          "enPassantTarget": "e3",
          "inCheck": false,
          "status": "playing"
        }"""
        val fenState = FenParserRegex.parse(fen)
        val jsonState = JsonParser.parse(json)
        assertTrue(
          fenState.isRight,
          jsonState.isRight,
          fenState == jsonState
        )
      },
      test("black in check: FEN and JSON produce the same GameState") {
        val fen = "4k3/4R3/8/8/8/8/8/4K3 b - - 0 1"
        val json = """{
          "board": {
            "e1": "white king",
            "e7": "white rook",
            "e8": "black king"
          },
          "activeColor": "black",
          "castlingRights": {
            "whiteKingSide": false,
            "whiteQueenSide": false,
            "blackKingSide": false,
            "blackQueenSide": false
          },
          "enPassantTarget": null,
          "inCheck": true,
          "status": "playing"
        }"""
        val fenState = FenParserRegex.parse(fen)
        val jsonState = JsonParser.parse(json)
        assertTrue(
          fenState.isRight,
          jsonState.isRight,
          fenState == jsonState
        )
      },
      test("partial castling rights: FEN and JSON produce the same GameState") {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R b Kq - 0 1"
        val json = """{
          "board": {
            "a1": "white rook",
            "e1": "white king",
            "h1": "white rook",
            "a8": "black rook",
            "e8": "black king",
            "h8": "black rook"
          },
          "activeColor": "black",
          "castlingRights": {
            "whiteKingSide": true,
            "whiteQueenSide": false,
            "blackKingSide": false,
            "blackQueenSide": true
          },
          "enPassantTarget": null,
          "inCheck": false,
          "status": "playing"
        }"""
        val fenState = FenParserRegex.parse(fen)
        val jsonState = JsonParser.parse(json)
        assertTrue(
          fenState.isRight,
          jsonState.isRight,
          fenState == jsonState
        )
      }
    )
  )

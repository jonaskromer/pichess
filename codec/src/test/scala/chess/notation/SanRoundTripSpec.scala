package chess.notation

import chess.model.board.{GameState, Move}
import chess.model.rules.Game
import zio.*
import zio.test.*

/** Full round-trip: for a real multi-move game, every move serialized by
  * [[SanSerializer]] must re-parse to the same `Move` via [[MoveParser]]
  * when fed the pre-move state. Catches asymmetries between the two codecs
  * (missing disambiguation, wrong capture-vs-push choice, mishandled castling,
  * etc.) that a one-directional test would miss.
  */
object SanRoundTripSpec extends ZIOSpecDefault:

  /** Play `moves` through `Game.applyMove` starting from `initial`, returning
    * the list of (pre-move-state, move) pairs.
    */
  private def playMoves(
      initial: GameState,
      moves: List[Move],
  ): IO[chess.model.GameError, List[(GameState, Move)]] =
    ZIO
      .foldLeft(moves)(List.empty[(GameState, Move)] -> initial) {
        case ((acc, pre), move) =>
          Game.applyMove(pre, move).map(next => ((pre, move) :: acc, next))
      }
      .map(_._1.reverse)

  /** For each (pre, move), SAN-serialize `move` in `pre`, parse that SAN in
    * `pre`, and assert it resolves to the same move.
    */
  private def assertRoundTrip(
      pairs: List[(GameState, Move)]
  ): IO[chess.model.GameError, TestResult] =
    ZIO
      .foreach(pairs) { case (pre, move) =>
        for
          san         <- SanSerializer.toSan(move, pre)
          reparsed    <- MoveParser.parse(san, pre)
        yield assertTrue(reparsed == move).label(s"SAN: $san")
      }
      .map(_.reduce(_ && _))

  def spec = suite("SAN round-trip")(
    test("Italian Game opening (6 half-moves)") {
      import chess.model.board.Position
      val moves = List(
        Move(Position('e', 2), Position('e', 4)),
        Move(Position('e', 7), Position('e', 5)),
        Move(Position('g', 1), Position('f', 3)),
        Move(Position('b', 8), Position('c', 6)),
        Move(Position('f', 1), Position('c', 4)),
        Move(Position('g', 8), Position('f', 6)),
      )
      for
        pairs  <- playMoves(GameState.initial, moves)
        result <- assertRoundTrip(pairs)
      yield result
    },
    test("kingside castling for both sides") {
      import chess.model.board.Position
      val moves = List(
        Move(Position('e', 2), Position('e', 4)),
        Move(Position('e', 7), Position('e', 5)),
        Move(Position('g', 1), Position('f', 3)),
        Move(Position('g', 8), Position('f', 6)),
        Move(Position('f', 1), Position('c', 4)),
        Move(Position('f', 8), Position('c', 5)),
        Move(Position('e', 1), Position('g', 1)),   // O-O
        Move(Position('e', 8), Position('g', 8)),   // O-O
      )
      for
        pairs  <- playMoves(GameState.initial, moves)
        result <- assertRoundTrip(pairs)
      yield result
    },
    test("promotion via push and capture") {
      import chess.model.board.Position
      import chess.model.piece.{Color, Piece, PieceType}
      // Kings tucked out of reach of any new queen/knight landing on the
      // back rank so we don't accidentally leave a king in check.
      val start = GameState(
        Map(
          Position('e', 4) -> Piece(Color.White, PieceType.King),
          Position('a', 3) -> Piece(Color.Black, PieceType.King),
          Position('a', 7) -> Piece(Color.White, PieceType.Pawn),
          Position('b', 8) -> Piece(Color.Black, PieceType.Rook),
          Position('h', 2) -> Piece(Color.Black, PieceType.Pawn),
          Position('g', 1) -> Piece(Color.White, PieceType.Knight),
        ),
        Color.White,
      )
      val moves = List(
        Move(Position('a', 7), Position('b', 8), Some(PieceType.Queen)), // axb8=Q
        Move(Position('h', 2), Position('g', 1), Some(PieceType.Knight)), // hxg1=N
      )
      for
        pairs  <- playMoves(start, moves)
        result <- assertRoundTrip(pairs)
      yield result
    },
    test("knight disambiguation by file") {
      import chess.model.board.Position
      import chess.model.piece.{Color, Piece, PieceType}
      // Two white knights on b1 and d1, both can reach c3 → need file
      // disambiguation since they share a rank but differ in file.
      val start = GameState(
        Map(
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King),
          Position('b', 1) -> Piece(Color.White, PieceType.Knight),
          Position('d', 1) -> Piece(Color.White, PieceType.Knight),
        ),
        Color.White,
      )
      val moves = List(
        Move(Position('b', 1), Position('c', 3)), // Nbc3
      )
      for
        pairs  <- playMoves(start, moves)
        result <- assertRoundTrip(pairs)
      yield result
    },
    test("knight disambiguation by rank") {
      import chess.model.board.Position
      import chess.model.piece.{Color, Piece, PieceType}
      // Two white knights on g1 and g5 share the g file; both reach f3.
      // SAN must rank-disambiguate as "N1f3".
      val start = GameState(
        Map(
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King),
          Position('g', 1) -> Piece(Color.White, PieceType.Knight),
          Position('g', 5) -> Piece(Color.White, PieceType.Knight),
        ),
        Color.White,
      )
      val moves = List(
        Move(Position('g', 1), Position('f', 3)), // N1f3
      )
      for
        pairs  <- playMoves(start, moves)
        result <- assertRoundTrip(pairs)
      yield result
    },
  )

package chess.codec

import chess.notation.MoveParser
import chess.model.GameError
import chess.model.board.{CastlingRights, GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.rules.Game
import zio.{IO, ZIO}
import zio.test.*

/** Shared test suite for any `identity: GameState => K` function.
  *
  * Every implementation of position-identity (currently
  * [[FenSerializer.positionKey]], soon [[chess.model.rules.Zobrist.hash]]) must
  * satisfy the same contract — they disambiguate positions identically up to
  * FIDE's "two positions are the same when…" rule.
  *
  * Mixed in by [[PositionKeySensitivitySpec]] and
  * [[chess.model.rules.ZobristSpec]] via the [[FenParserBehaviors]] pattern.
  */
object PositionIdentityBehaviors:

  private def replay(
      initial: GameState,
      moves: List[String]
  ): IO[GameError, GameState] =
    ZIO.foldLeft(moves)(initial) { (state, san) =>
      for
        move <- MoveParser.parse(san, state)
        next <- Game.applyMove(state, move)
      yield next
    }

  /** Build the spec instantiated against a specific identity function.
    *
    * The identity's return type `K` is opaque — only `!=` and `==` comparisons
    * are used in the tests.
    */
  def behaviors[K](identity: GameState => K): Spec[Any, GameError] =
    suite("position-identity contract")(
      suite("sensitivity — any field change produces a different key")(
        test("piece placement: moving a pawn changes the key") {
          val base = GameState.initial
          val modified = base.copy(board =
            base.board - Position('e', 2) +
              (Position('e', 4) -> Piece(Color.White, PieceType.Pawn))
          )
          assertTrue(identity(base) != identity(modified))
        },
        test(
          "piece type: same square, different piece type changes the key"
        ) {
          val base = GameState.initial
          val modified = base.copy(board =
            base.board + (Position('a', 1) -> Piece(
              Color.White,
              PieceType.Knight
            ))
          )
          assertTrue(identity(base) != identity(modified))
        },
        test(
          "piece color: same square, same type, different color changes the key"
        ) {
          val base = GameState.initial
          val modified = base.copy(board =
            base.board + (Position('a', 1) -> Piece(
              Color.Black,
              PieceType.Rook
            ))
          )
          assertTrue(identity(base) != identity(modified))
        },
        test("active color: White vs Black changes the key") {
          val base = GameState.initial
          val flipped = base.copy(activeColor = Color.Black)
          assertTrue(identity(base) != identity(flipped))
        },
        test("castling: whiteKingSide flag alone changes the key") {
          val base = GameState.initial
          val mod = base.copy(castlingRights =
            base.castlingRights.copy(whiteKingSide = false)
          )
          assertTrue(identity(base) != identity(mod))
        },
        test("castling: whiteQueenSide flag alone changes the key") {
          val base = GameState.initial
          val mod = base.copy(castlingRights =
            base.castlingRights.copy(whiteQueenSide = false)
          )
          assertTrue(identity(base) != identity(mod))
        },
        test("castling: blackKingSide flag alone changes the key") {
          val base = GameState.initial
          val mod = base.copy(castlingRights =
            base.castlingRights.copy(blackKingSide = false)
          )
          assertTrue(identity(base) != identity(mod))
        },
        test("castling: blackQueenSide flag alone changes the key") {
          val base = GameState.initial
          val mod = base.copy(castlingRights =
            base.castlingRights.copy(blackQueenSide = false)
          )
          assertTrue(identity(base) != identity(mod))
        },
        test("castling: all four flipped are distinct from none flipped") {
          val base = GameState.initial
          val mod = base.copy(castlingRights =
            CastlingRights(false, false, false, false)
          )
          assertTrue(identity(base) != identity(mod))
        },
        test("en passant: absent vs present changes the key") {
          val base = GameState.initial
          val withEp = base.copy(enPassantTarget = Some(Position('e', 3)))
          assertTrue(identity(base) != identity(withEp))
        },
        test("en passant: different target files produce different keys") {
          val d3 = GameState.initial.copy(enPassantTarget =
            Some(Position('d', 3))
          )
          val e3 = GameState.initial.copy(enPassantTarget =
            Some(Position('e', 3))
          )
          assertTrue(identity(d3) != identity(e3))
        }
      ),
      suite("exhaustive distinctness — full spaces map 1:1")(
        test(
          "a white pawn placed on any of the 62 non-king squares yields a distinct key"
        ) {
          // Minimal valid-ish backdrop (two kings) so we're not mixing effects
          // from other pieces. Add a pawn at every free square; assert all
          // keys are distinct. Catches square-index bugs that collide any two
          // squares (e.g., wrong multiplication, ignoring file).
          val backdrop = GameState(
            board = Map(
              Position('e', 1) -> Piece(Color.White, PieceType.King),
              Position('e', 8) -> Piece(Color.Black, PieceType.King)
            ),
            activeColor = Color.White,
            castlingRights = CastlingRights(false, false, false, false)
          )
          val squares = for
            col <- ('a' to 'h').toList
            row <- (1 to 8).toList
            pos = Position(col, row)
            if !backdrop.board.contains(pos)
          yield pos
          val keys = squares.map(pos =>
            identity(
              backdrop.copy(board =
                backdrop.board + (pos -> Piece(Color.White, PieceType.Pawn))
              )
            )
          )
          assertTrue(keys.distinct.size == keys.size)
        },
        test(
          "every (piece type × color) on the same square yields a distinct key"
        ) {
          // Place each of the 12 (type × color) combinations on a3 and assert
          // all 12 keys differ. Catches bugs that collide piece-type indices.
          val backdrop = GameState(
            board = Map(
              Position('e', 1) -> Piece(Color.White, PieceType.King),
              Position('e', 8) -> Piece(Color.Black, PieceType.King)
            ),
            activeColor = Color.White,
            castlingRights = CastlingRights(false, false, false, false)
          )
          val target = Position('a', 3)
          val pieces = for
            color <- Color.values.toList
            pt <- PieceType.values.toList
          yield Piece(color, pt)
          val keys = pieces.map(p =>
            identity(backdrop.copy(board = backdrop.board + (target -> p)))
          )
          assertTrue(keys.distinct.size == 12)
        },
        test("each en passant file yields a distinct key") {
          val base = GameState.initial
          val keys = ('a' to 'h').toList.map(c =>
            identity(base.copy(enPassantTarget = Some(Position(c, 3))))
          )
          assertTrue(keys.distinct.size == 8)
        },
        test("each of the 16 castling combinations yields a distinct key") {
          val base = GameState.initial
          val combos = for
            wK <- List(true, false)
            wQ <- List(true, false)
            bK <- List(true, false)
            bQ <- List(true, false)
          yield CastlingRights(wK, wQ, bK, bQ)
          val keys = combos.map(c => identity(base.copy(castlingRights = c)))
          assertTrue(keys.distinct.size == 16)
        },
        test(
          "piece placement is sensitive to file (same-rank move changes the key)"
        ) {
          // Complement to the existing e2→e4 test (same file, different
          // rank). Move a piece to a different file on the same rank and
          // assert the key differs — catches square-index bugs that drop the
          // file contribution.
          val base = GameState.initial
          val modified = base.copy(board =
            base.board - Position('b', 1) +
              (Position('c', 1) -> Piece(Color.White, PieceType.Knight))
          )
          assertTrue(identity(base) != identity(modified))
        }
      ),
      suite("symmetry — structurally identical positions have equal keys")(
        test("transposition: same position reached via different move orders") {
          val routeA = List("e4", "e5", "Nf3", "Nc6", "Bb5")
          val routeB = List("Nf3", "Nc6", "e4", "e5", "Bb5")
          for
            stateA <- replay(GameState.initial, routeA)
            stateB <- replay(GameState.initial, routeB)
          yield assertTrue(
            identity(stateA) == identity(stateB),
            stateA.halfmoveClock != stateB.halfmoveClock
          )
        },
        test("idempotence: two calls on the same state return the same key") {
          val s = GameState.initial
          assertTrue(identity(s) == identity(s))
        },
        test("halfmove/fullmove differences do not affect the key") {
          val base = GameState.initial
          val clocked = base.copy(halfmoveClock = 42, fullmoveNumber = 99)
          assertTrue(identity(base) == identity(clocked))
        },
        test("status and inCheck differences do not affect the key") {
          val base = GameState.initial
          val withCheck = base.copy(inCheck = true)
          assertTrue(identity(base) == identity(withCheck))
        }
      )
    )

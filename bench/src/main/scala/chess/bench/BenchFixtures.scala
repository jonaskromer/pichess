package chess.bench
import chess.model.board.{GameState, Move}
import chess.model.rules.Game
import chess.notation.MoveParser

/** Shared test inputs for the JMH benchmarks. Constructed once at class
  * init so the per-invocation cost of each benchmark is the function under
  * test, not the fixture setup.
  *
  * The PGN corpus mirrors the curated sequences used by
  * `RepetitionEquivalenceSpec` so the bench exercises the same edge cases
  * the unit tests cover (castling both sides, en passant, knight shuffle,
  * scholar's mate).
  */
object BenchFixtures:

  /** A handful of FEN strings spanning representative positions:
    *   - starting position (board is full)
    *   - mid-game with most pieces still present (Ruy Lopez)
    *   - "Kiwipete" perft position (CR + EP + checks possible)
    *   - lone-king-and-queen endgame (sparse board)
    *   - pawn-only structure (every move is a pawn push)
    */
  val fenCorpus: List[String] = List(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "4k3/8/8/8/8/8/8/3QK3 w - - 0 1",
    "8/pppppppp/8/8/8/8/PPPPPPPP/8 w - - 0 1",
  )

  val startingFen: String = fenCorpus.head

  /** The starting [[GameState]] — used as the baseline for Game.applyMove
    * + Zobrist + SAN benchmarks.
    */
  val startingState: GameState = GameState.initial

  /** A handful of well-formed legal moves from the starting position, in
    * coordinate notation. Each is pre-parsed once so the benchmark body
    * doesn't include parsing overhead.
    */
  val openingMoves: List[Move] =
    List("e2 e4", "g1 f3", "f1 c4", "d2 d4", "b1 c3").map { s =>
      UnsafeRuntime.run(MoveParser.parse(s, startingState))
    }

  /** Curated SAN move sequences. Mirrors `RepetitionEquivalenceSpec`'s
    * corpus so the bench surfaces regressions in any of the same edge
    * cases the unit tests cover.
    *
    * Declared before [[midGameState]] because that field consumes the
    * `ruyLopez` sequence at class-init time.
    */
  val sanSequences: Map[String, List[String]] = Map(
    "ruyLopez" -> List(
      "e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6",
      "O-O", "Be7", "Re1", "b5", "Bb3", "d6", "c3", "O-O",
    ),
    "najdorf" -> List(
      "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6",
      "Nc3", "a6", "Be3", "e5", "Nb3", "Be6", "f3", "b5",
      "Qd2", "Nbd7", "O-O-O", "Be7", "g4", "O-O",
    ),
    "enPassant"     -> List("e4", "Nf6", "e5", "d5", "exd6"),
    "scholarsMate"  -> List("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#"),
    "knightShuffle" -> List(
      "Nf3", "Nc6", "Ng1", "Nb8", "Nf3", "Nc6", "Ng1", "Nb8",
    ),
  )

  /** A mid-game [[GameState]] reached by playing the Ruy Lopez opening
    * for 8 plies. Used by benchmarks that want a board with more
    * non-trivial geometry than the start position offers (pinned pieces,
    * developed minors, castling rights still alive).
    */
  val midGameState: GameState =
    sanSequences("ruyLopez").take(8).foldLeft(startingState) { (st, san) =>
      val m = UnsafeRuntime.run(MoveParser.parse(san, st))
      UnsafeRuntime.run(Game.applyMove(st, m))
    }

  /** Wraps each SAN sequence in a minimal PGN envelope so [[PgnParser]]
    * can ingest it. Result tag is open-ended so an unfinished sequence
    * is still well-formed.
    */
  val pgnCorpus: Map[String, String] =
    sanSequences.map { case (name, moves) =>
      val movetext = moves.zipWithIndex
        .map { case (san, i) => if i % 2 == 0 then s"${i / 2 + 1}. $san" else san }
        .mkString(" ")
      val pgn = s"""[Event "$name"]\n[Result "*"]\n\n$movetext *"""
      name -> pgn
    }

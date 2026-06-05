package chess.bot.train

import chess.codec.FenParserRegex
import chess.model.board.GameState

/** A curated set of opening positions used to diversify
  * tournament games — without this, two similar Search instances
  * playing from [[GameState.initial]] tend to converge on the
  * same line every game, and the round terminates as 90%+ draws.
  *
  * Each entry is the FEN of a well-known opening reached after
  * 3-5 plies. Both colours of the matchup play out from there,
  * so a single FEN drives two games (challenger white + champion
  * white). The pool is hand-curated to favour positions where the
  * eval can credibly differ — open and semi-open positions where
  * material, mobility, and king-safety features all have signal.
  *
  * Used by [[Tournament.play]] when `useOpenings = true` (the
  * default). Index `i` of the round picks
  * `openings(i / 2 % openings.length)` — the colour swap on every
  * pair means each opening is exercised from both sides over a
  * 2-game window.
  */
object OpeningPool:

  /** Twelve openings, each ~6 plies deep. Mix of e4 and d4
    * complexes. Format: FEN at the position the tournament
    * starts from. */
  val fens: Vector[String] = Vector(
    // 1. Ruy Lopez (1.e4 e5 2.Nf3 Nc6 3.Bb5)
    "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",
    // 2. Italian (1.e4 e5 2.Nf3 Nc6 3.Bc4)
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",
    // 3. Sicilian Najdorf (1.e4 c5 2.Nf3 d6 3.d4 cxd4 4.Nxd4 Nf6 5.Nc3 a6)
    "rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6",
    // 4. French Tarrasch (1.e4 e6 2.d4 d5 3.Nd2 c5)
    "rnbqkbnr/pp3ppp/4p3/2pp4/3PP3/8/PPPN1PPP/R1BQKBNR w KQkq c6 0 4",
    // 5. Caro-Kann Classical (1.e4 c6 2.d4 d5 3.Nc3 dxe4 4.Nxe4 Bf5)
    "rn1qkbnr/pp2pppp/2p5/8/3PNb2/8/PPP2PPP/R1BQKBNR w KQkq - 1 5",
    // 6. Queen's Gambit Declined (1.d4 d5 2.c4 e6 3.Nc3 Nf6)
    "rnbqkb1r/ppp2ppp/4pn2/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 1 4",
    // 7. King's Indian Defence (1.d4 Nf6 2.c4 g6 3.Nc3 Bg7)
    "rnbqk2r/ppppppbp/5np1/8/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 3 4",
    // 8. Slav (1.d4 d5 2.c4 c6 3.Nf3 Nf6)
    "rnbqkb1r/pp2pppp/2p2n2/3p4/2PP4/5N2/PP2PPPP/RNBQKB1R w KQkq - 2 4",
    // 9. English (1.c4 e5 2.Nc3 Nf6)
    "rnbqkb1r/pppp1ppp/5n2/4p3/2P5/2N5/PP1PPPPP/R1BQKBNR w KQkq - 2 3",
    // 10. London System-ish (1.d4 d5 2.Nf3 Nf6 3.Bf4)
    "rnbqkb1r/ppp1pppp/5n2/3p4/3P1B2/5N2/PPP1PPPP/RN1QKB1R b KQkq - 3 3",
    // 11. Scandinavian (1.e4 d5 2.exd5 Qxd5 3.Nc3 Qa5)
    "rnb1kbnr/ppp1pppp/8/q7/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 4",
    // 12. Pirc (1.e4 d6 2.d4 Nf6 3.Nc3 g6)
    "rnbqkb1r/ppp1pp1p/3p1np1/8/3PP3/2N5/PPP2PPP/R1BQKBNR w KQkq - 0 4",
  )

  /** Parse a FEN by index — wraps `FenParserRegex.parse` which is
    * the canonical parser. Throws on out-of-bounds because the
    * indices are caller-internal (round-robin from a finite list);
    * the failure would be a defect, not user input. */
  def state(idx: Int): zio.IO[chess.model.GameError, GameState] =
    FenParserRegex.parse(fens(idx % fens.length))

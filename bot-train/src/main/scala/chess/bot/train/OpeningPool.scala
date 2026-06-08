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

  /** Sixty named openings, each 4-8 plies deep. Mix of every
    * major complex (1.e4, 1.d4, 1.c4, 1.Nf3, irregulars) plus
    * several lines per complex covering the canonical mainlines
    * and a handful of secondary tries. Format: FEN at the
    * position the tournament starts from.
    *
    * For NNUE data-gen the volume directly drives position
    * diversity — with 12 openings the same 12 pawn structures
    * dominate the corpus; with 60 we get the wider distribution
    * a real master corpus would carry. */
  val fens: Vector[String] = Vector(
    // ─── 1.e4 e5 (Open Games) ────────────────────────────────────
    "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",       // Ruy Lopez
    "r1bqkb1r/pppp1ppp/2n2n2/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",     // Ruy Lopez Berlin
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",       // Italian
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R b KQkq - 0 4",     // Italian Four-Knights
    "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",        // King's Knight Opening
    "r1bqkbnr/pp1p1ppp/2n5/2p1p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 3",      // Vienna Game
    "rnbqkbnr/ppp2ppp/8/3pp3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3",          // Petroff Defense
    "rnbqkbnr/pp1ppppp/8/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 3",            // Scotch (open)
    // ─── 1.e4 c5 (Sicilian Defense) ──────────────────────────────
    "rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6",        // Najdorf
    "rnbqkb1r/pp2pp1p/3p1np1/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6",        // Dragon
    "r1bqkbnr/pp1ppppp/2n5/8/3NP3/8/PPP2PPP/RNBQKB1R b KQkq - 1 3",            // Sicilian Sveshnikov-ish open
    "rnbqkbnr/pp1ppppp/8/2p5/2P1P3/8/PP1P1PPP/RNBQKBNR b KQkq - 0 2",          // Sicilian Wing Gambit
    "rnbqkbnr/pp1ppppp/8/2p5/4P3/2N5/PPPP1PPP/R1BQKBNR b KQkq - 1 2",          // Closed Sicilian (Nc3)
    // ─── 1.e4 e6 (French) ────────────────────────────────────────
    "rnbqkbnr/pp3ppp/4p3/2pp4/3PP3/8/PPPN1PPP/R1BQKBNR w KQkq c6 0 4",         // French Tarrasch
    "rnbqkb1r/pp1ppppp/4pn2/8/3PP3/2N5/PPP2PPP/R1BQKBNR w KQkq - 1 4",         // French Winawer setup
    "rnbqkbnr/pp1p1ppp/4p3/2p5/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 0 3",          // French Advance idea
    // ─── 1.e4 c6 (Caro-Kann) ────────────────────────────────────
    "rn1qkbnr/pp2pppp/2p5/8/3PNb2/8/PPP2PPP/R1BQKBNR w KQkq - 1 5",            // Caro-Kann Classical
    "rnbqkbnr/pp2pppp/2p5/3p4/2PPP3/8/PP3PPP/RNBQKBNR b KQkq - 0 3",           // Caro-Kann Panov
    "rnbqkbnr/pp2pppp/2p5/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2",             // Caro-Kann Exchange
    // ─── Other 1.e4 ─────────────────────────────────────────────
    "rnb1kbnr/ppp1pppp/8/q7/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 4",             // Scandinavian Qa5
    "rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2",              // Scandinavian Exchange
    "rnbqkb1r/ppp1pp1p/3p1np1/8/3PP3/2N5/PPP2PPP/R1BQKBNR w KQkq - 0 4",       // Pirc
    "rnbqkbnr/ppp1pppp/3p4/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",            // Modern Defense base
    "rnbqkb1r/pppp1ppp/5n2/4p3/4P3/8/PPPPNPPP/RNBQKB1R b KQkq - 1 2",          // Alekhine setup
    "rnbqkb1r/pppppp1p/5np1/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 1 2",           // Modern e4-g6
    // ─── 1.d4 d5 ────────────────────────────────────────────────
    "rnbqkb1r/ppp2ppp/4pn2/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 1 4",        // QGD Mainline
    "rnbqkb1r/pp2pppp/2p2n2/3p4/2PP4/5N2/PP2PPPP/RNBQKB1R w KQkq - 2 4",       // Slav Mainline
    "rnbqkbnr/pp2pppp/8/2pP4/8/8/PPP1PPPP/RNBQKBNR b KQkq - 0 3",              // QGD Exchange-ish
    "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2",            // Generic 1.d4 d5
    "rnbqkb1r/ppp1pppp/5n2/3p4/3P1B2/5N2/PPP1PPPP/RN1QKB1R b KQkq - 3 3",      // London System
    "rnbqkb1r/ppp2ppp/4pn2/3pP3/3P4/8/PPP2PPP/RNBQKBNR b KQkq - 0 4",          // French-Stonewall-ish
    "rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2",           // Queen's Gambit
    "rnbqkb1r/pppp1ppp/4pn2/8/2PP4/2N5/PP2PPPP/R1BQKBNR b KQkq - 1 3",         // QGD Indian-style
    // ─── 1.d4 Nf6 ───────────────────────────────────────────────
    "rnbqk2r/ppppppbp/5np1/8/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 3 4",          // King's Indian Defence
    "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 3 4",         // Nimzo-Indian
    "rnbqkb1r/pppppp1p/5np1/8/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3",           // Indian g6
    "rnbqkb1r/pp1ppppp/5n2/2p5/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3",          // Benoni setup
    "rnbqkb1r/pp2pppp/3p1n2/2pP4/2P5/8/PP2PPPP/RNBQKBNR w KQkq - 0 4",         // Benoni Modern
    "rnbqkb1r/p1pp1ppp/1p2pn2/8/2PP4/5N2/PP2PPPP/RNBQKB1R w KQkq - 0 4",       // Queen's Indian
    "rnbqkb1r/pppppp1p/5np1/8/3P4/5N2/PPP1PPPP/RNBQKB1R w KQkq - 0 3",         // King's Indian (no c4)
    "rnbqkbnr/pppppppp/8/8/3P4/5N2/PPP1PPPP/RNBQKB1R b KQkq - 1 2",            // Réti-like d-pawn
    // ─── 1.c4 ───────────────────────────────────────────────────
    "rnbqkb1r/pppp1ppp/5n2/4p3/2P5/2N5/PP1PPPPP/R1BQKBNR w KQkq - 2 3",        // English Symmetric
    "rnbqkbnr/pp1ppppp/8/2p5/2P5/2N5/PP1PPPPP/R1BQKBNR b KQkq - 1 2",          // English Anti-Sicilian
    "rnbqkbnr/pppp1ppp/8/4p3/2P5/8/PP1PPPPP/RNBQKBNR w KQkq - 0 2",            // English (1.c4 e5)
    "rnbqkb1r/pppppp1p/5np1/8/2P5/5N2/PP1PPPPP/RNBQKB1R w KQkq - 2 3",         // English KID setup
    // ─── 1.Nf3 ──────────────────────────────────────────────────
    "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1",              // Réti
    "rnbqkb1r/pppppp1p/5np1/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 1 2",           // King's Indian Attack
    // ─── Less mainstream / irregular ────────────────────────────
    "rnbqkbnr/pppp1ppp/8/4p3/5P2/8/PPPPP1PP/RNBQKBNR b KQkq - 0 1",            // King's Pawn-like irreg
    "rnbqkbnr/ppp1pppp/3p4/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2",            // 1.d4 d6 setup
    "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2",            // From's Gambit reach
    "rnbqkbnr/pppppppp/8/8/2P5/8/PP1PPPPP/RNBQKBNR b KQkq - 0 1",              // English open
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",                // Initial position (rare anchor)
    // ─── Sharper middlegame jumping-off points ──────────────────
    "r1bqkbnr/pppp1ppp/2n5/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 4",          // Scotch open-center
    "r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4",    // Four Knights symmetric
    "rnbqkb1r/pp1ppp1p/5np1/2p5/2P5/2N3P1/PP1PPP1P/R1BQKBNR w KQkq - 0 4",     // English Fianchetto
    "rnbqkb1r/ppp1pppp/5n2/3p4/3PP3/8/PPP2PPP/RNBQKBNR b KQkq - 0 3",          // BDG-like center
    "rnbq1rk1/pppp1ppp/4pn2/8/1bPP4/2N1P3/PP3PPP/R1BQKBNR w KQ - 1 5",         // Nimzo deeper
    "r1bqkbnr/pppp1ppp/2n5/4p3/2P1P3/2N5/PP1P1PPP/R1BQKBNR b KQkq - 1 3",      // English-Italian transposition
    "rnbqkb1r/pp1ppp1p/5np1/2p5/2PPP3/2N5/PP3PPP/R1BQKBNR b KQkq - 0 4",       // KID main
    "rnbqkb1r/pp1ppppp/5n2/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 4",          // Sicilian open transposition
    "rnbqkb1r/pp2pppp/2p2n2/3p2B1/3P4/2N5/PPP1PPPP/R2QKBNR b KQkq - 3 4",      // Trompowsky-ish
    "rnbqkbnr/ppp2ppp/4p3/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 0 3",           // French Exchange
    "rnbqkbnr/ppp2ppp/8/3pp3/2P5/2N5/PP1PPPPP/R1BQKBNR w KQkq - 0 3",          // English vs e5/d5
  )

  /** Parse a FEN by index — wraps `FenParserRegex.parse` which is
    * the canonical parser. Throws on out-of-bounds because the
    * indices are caller-internal (round-robin from a finite list);
    * the failure would be a defect, not user input. */
  def state(idx: Int): zio.IO[chess.model.GameError, GameState] =
    FenParserRegex.parse(fens(idx % fens.length))

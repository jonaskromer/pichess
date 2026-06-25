package chess.analysis

import zio.test.*

import chess.bot.engine.{Evaluator, Search}
import chess.codec.PgnParser
import chess.model.board.GameState
import chess.opening.{EcoBook, EcoEntry}

object GameAnalyzerSpec extends ZIOSpecDefault:

  private val search: Search = Search.alphaBeta(Evaluator.materialOnly)
  // small book: first two plies of the Sicilian are "book"
  private val eco = EcoBook.fromEntries(
    Vector(EcoEntry("B20", "Sicilian Defense", List("e4", "c5")))
  )
  private val analyzer = GameAnalyzer(search, eco)

  def spec = suite("GameAnalyzer")(
    test("rates a short game: opening, book moves, colours, accuracy") {
      val pgn = "1. e4 c5 2. Nf3 d6 *"
      for
        game     <- PgnParser.parse(pgn)
        analysis <- analyzer.analyze(game.initialState, game.history, depth = 2)
      yield assertTrue(
        analysis.moves.length == 4,
        analysis.opening.name == "Sicilian Defense",
        analysis.moves(0).moveClass == MoveClass.Book, // ply 0 < plyMatched 2
        analysis.moves(1).moveClass == MoveClass.Book,
        analysis.moves(2).moveClass != MoveClass.Book, // beyond book
        analysis.moves(0).color == "white",
        analysis.moves(1).color == "black",
        analysis.moves(2).san == "Nf3",
        analysis.moves(2).bestMove.nonEmpty,
        analysis.accuracyWhite >= 0.0 && analysis.accuracyWhite <= 100.0,
        analysis.accuracyBlack >= 0.0 && analysis.accuracyBlack <= 100.0
      )
    },
    test("empty game → no moves, no opening, full accuracy") {
      for analysis <- analyzer.analyze(GameState.initial, Nil, depth = 2)
      yield assertTrue(
        analysis.moves.isEmpty,
        analysis.opening == chess.opening.Opening.none,
        analysis.accuracyWhite == 100.0,
        analysis.accuracyBlack == 100.0
      )
    },
    test("a forced single-legal-move position analyses (gap fallback)") {
      // Black king a8 has exactly one legal move, Ka7 → bestMoves returns one
      // entry, exercising the 'no second move' gap branch.
      val pgn = "[FEN \"k7/8/2K5/8/8/8/8/1R6 b - - 0 1\"]\n\n1... Ka7 *"
      for
        game     <- PgnParser.parse(pgn)
        analysis <- analyzer.analyze(game.initialState, game.history, depth = 1)
      yield assertTrue(
        analysis.moves.length == 1,
        analysis.moves.head.color == "black",
        analysis.moves.head.san == "Ka7"
      )
    },
    test("stalemating a won position scores as a blunder (terminal = 0)") {
      // White is up a queen and stalemates with Qg6 — the played move ends the
      // game in a draw, so its value is a dead-even 0 against a ~+900 best,
      // exercising the non-checkmate terminal branch and the win-throwaway case.
      val pgn = "[FEN \"7k/5K2/8/8/8/8/8/6Q1 w - - 0 1\"]\n\n1. Qg6 *"
      for
        game     <- PgnParser.parse(pgn)
        analysis <- analyzer.analyze(game.initialState, game.history, depth = 2)
      yield assertTrue(
        analysis.moves.length == 1,
        analysis.moves.head.san == "Qg6",
        analysis.moves.head.evalCp == 0,
        analysis.moves.head.moveClass == MoveClass.Blunder
      )
    }
  )

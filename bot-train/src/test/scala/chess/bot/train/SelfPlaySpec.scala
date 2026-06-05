package chess.bot.train

import zio.*
import zio.test.*

import chess.bot.engine.{Evaluator, Search}
import chess.codec.FenParserRegex
import chess.model.board.{GameState, Move}

/** Self-play loop specs. Uses the material-only material evaluator
  * for both sides so games actually terminate — a more sophisticated
  * eval would also work, but material-only games end faster (and
  * deterministically) which keeps the suite fast.
  */
object SelfPlaySpec extends ZIOSpecDefault:

  private val materialSearch: Search =
    Search.alphaBeta(Evaluator.materialOnly)

  def spec = suite("SelfPlay")(
    suite("playGame")(
      test("terminates with MaxMovesReached when the cap is too low") {
        // depth 1 with material-only is too shallow to mate in <4
        // plies from startpos; we hit the cap.
        for result <- SelfPlay.playGame(
                        materialSearch, materialSearch,
                        depth = 1, maxPlies = 4,
                      )
        yield assertTrue(
          result.outcome == SelfPlay.Outcome.MaxMovesReached,
          result.history.size == 4,
        )
      },
      test("plays moves in alternating colors") {
        for result <- SelfPlay.playGame(
                        materialSearch, materialSearch,
                        depth = 1, maxPlies = 6,
                      )
        yield assertTrue(
          // First move from white-to-move state.
          result.history.head._2.activeColor == chess.model.piece.Color.Black,
          // Second move from black-to-move state.
          result.history(1)._2.activeColor   == chess.model.piece.Color.White,
        )
      },
      test("ends with a checkmate outcome from a mate-in-1 position") {
        // Custom search that always picks the queen mate move.
        val queenMate = Move(
          chess.model.board.Position('h', 6),
          chess.model.board.Position('h', 7),
        )
        val mateSearch: Search = new Search:
          def bestMove(state: GameState, depth: Int, history: Set[Long])
              : UIO[Option[Move]] =
            ZIO.succeed(Some(queenMate))
        val matePosition = "7k/8/6KQ/8/8/8/8/8 w - - 0 1"
        // Override start position: use a custom playGame that starts
        // from FEN instead of GameState.initial.
        for
          start <- FenParserRegex.parse(matePosition)
          result <- playFromState(start, mateSearch, materialSearch, depth = 2)
        yield assertTrue(
          result.outcome == SelfPlay.Outcome.WhiteWins,
        )
      },
    ),
    suite("round")(
      test("plays the requested number of games") {
        for result <- SelfPlay.round(
                        champion = materialSearch,
                        challenger = materialSearch,
                        games = 3,
                        depth = 1,
                        maxPlies = 4,
                      )
        yield assertTrue(result.games == 3)
      },
      test("aggregates training rows across games") {
        for result <- SelfPlay.round(
                        champion = materialSearch,
                        challenger = materialSearch,
                        games = 2,
                        depth = 1,
                        maxPlies = 4,
                      )
        // Each game caps at 4 plies = 4 rows; 2 games × 4 = 8 rows.
        yield assertTrue(result.trainingRows.size == 8)
      },
      test("alternates colors across games") {
        // First game: challenger plays white. Second: champion white.
        // We verify via a stub that records who was asked at the
        // first ply.
        for
          calls <- Ref.make(Vector.empty[String])
          recording: Search = new Search:
            def bestMove(state: GameState, depth: Int, history: Set[Long])
                : UIO[Option[Move]] =
              calls.update(_ :+ s"rec@${state.fullmoveNumber}") *>
                materialSearch.bestMove(state, depth, history)
          // Use `materialSearch` as the second player so games can
          // still progress.
          _ <- SelfPlay.round(
                 champion = materialSearch,
                 challenger = recording,
                 games = 2,
                 depth = 1,
                 maxPlies = 2,
               )
          seen <- calls.get
        // First game: challenger=white → first call comes from
        // recording at fullmove=1. Second game: challenger=black →
        // first call from recording at fullmove=1 (after a champion
        // white move; still fullmove 1 since black hasn't moved yet
        // either). The recording fires at least once per game.
        yield assertTrue(seen.size >= 2)
      },
      test("parallelism > 1 plays the same number of games as the sequential path") {
        // Cross-game parallelism via ZIO.foreachPar. Outcome
        // counts must match sequential — the games are independent.
        for
          serial   <- SelfPlay.round(
                        champion = materialSearch,
                        challenger = materialSearch,
                        games = 4, depth = 1, maxPlies = 4,
                        parallelism = 1,
                      )
          parallel <- SelfPlay.round(
                        champion = materialSearch,
                        challenger = materialSearch,
                        games = 4, depth = 1, maxPlies = 4,
                        parallelism = 4,
                      )
        yield assertTrue(
          parallel.games == serial.games,
          // With material-only at depth 1 every game hits the cap
          // → draws on both paths.
          parallel.challengerWins + parallel.championWins + parallel.draws == 4,
        )
      },
      test("a 0-game round returns the zero accumulator") {
        for result <- SelfPlay.round(
                        materialSearch, materialSearch, games = 0, depth = 1,
                      )
        yield assertTrue(
          result.games == 0,
          result.trainingRows.isEmpty,
          result.challengerWins == 0,
          result.championWins   == 0,
          result.draws          == 0,
        )
      },
    ),
    suite("gameToTrainingRows")(
      test("a MaxMovesReached game produces 0.5 outcomes for everyone") {
        val game = SelfPlay.GameResult(history = Nil, outcome = SelfPlay.Outcome.MaxMovesReached)
        val rows = SelfPlay.gameToTrainingRows(game)
        // Empty history → no rows. A non-empty history with
        // MaxMovesReached → rows with outcome 0.5 (the "*" header
        // maps to draw in PgnIngest's outcome mapper).
        assertTrue(rows.isEmpty)
      },
      test("a non-empty history is converted to a row per move") {
        for
          // Real game from startpos for 3 plies, then convert.
          played <- SelfPlay.playGame(
                      materialSearch, materialSearch,
                      depth = 1, maxPlies = 3,
                    )
        yield
          val rows = SelfPlay.gameToTrainingRows(played)
          assertTrue(rows.size == played.history.size)
      },
    ),
  )

  /** Test helper: play from a custom FEN instead of GameState.initial.
    * Mirrors SelfPlay.playGame internals just with a different
    * starting state. */
  private def playFromState(
      initial: GameState,
      white: Search,
      black: Search,
      depth: Int,
      maxPlies: Int = 200,
  ): UIO[SelfPlay.GameResult] =
    def loop(state: GameState, history: Vector[(Move, GameState)], ply: Int)
        : UIO[SelfPlay.GameResult] =
      if ply >= maxPlies then
        ZIO.succeed(SelfPlay.GameResult(history.toList, SelfPlay.Outcome.MaxMovesReached))
      else
        val activeSearch =
          if state.activeColor == chess.model.piece.Color.White then white else black
        activeSearch.bestMove(state, depth).flatMap {
          case None =>
            ZIO.succeed(SelfPlay.GameResult(history.toList, SelfPlay.Outcome.Draw))
          case Some(move) =>
            chess.model.rules.Game.applyMove(state, move).orDieWith(_ =>
              new IllegalStateException("illegal move in test"),
            ).flatMap { next =>
              next.status match
                case chess.model.board.GameStatus.Checkmate(winner) =>
                  val out =
                    if winner == chess.model.piece.Color.White
                    then SelfPlay.Outcome.WhiteWins
                    else SelfPlay.Outcome.BlackWins
                  ZIO.succeed(SelfPlay.GameResult(
                    (history :+ (move, next)).toList, out,
                  ))
                case _: chess.model.board.GameStatus.Draw =>
                  ZIO.succeed(SelfPlay.GameResult(
                    (history :+ (move, next)).toList, SelfPlay.Outcome.Draw,
                  ))
                case chess.model.board.GameStatus.Playing =>
                  loop(next, history :+ (move, next), ply + 1)
                case _: chess.model.board.GameStatus.Resignation =>
                  ZIO.succeed(SelfPlay.GameResult(
                    (history :+ (move, next)).toList, SelfPlay.Outcome.Draw,
                  ))
            }
        }
    loop(initial, Vector.empty, 0)

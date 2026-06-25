package chess.bot.train

import zio.*

import chess.bot.data.TrainingRow
import chess.bot.engine.Search
import chess.codec.PgnParser
import chess.model.board.{GameState, GameStatus, Move}
import chess.model.piece.Color
import chess.model.rules.Game

/** Self-play loop.
  *
  * Two searches face off over N games (colors alternate to remove the
  * first-move bias); each completed game's move history is turned
  * into a vector of [[chess.bot.data.TrainingRow]] keyed by the
  * eventual outcome. The caller takes those rows, persists them via
  * [[chess.bot.data.TrainingRepo.appendBatch]], and re-runs
  * [[TexelTuner]] on the union of "old training corpus + self-play
  * deltas" to land a new weights snapshot.
  *
  * What this module deliberately doesn't do:
  *   - persist anything itself (the orchestrator decides when /
  *     where to write — keeps tests pure)
  *   - run the tuner (separate concern, separate testable unit)
  *   - manage weight versioning (lives in
  *     [[chess.bot.data.WeightsRepo]])
  */
object SelfPlay:

  enum Outcome:
    case WhiteWins, BlackWins, Draw, MaxMovesReached

  /** One played-out game. `history` is the same shape as
    * [[chess.codec.PgnParser.PgnGame.history]] so the row-building
    * logic in [[PgnIngest.buildRows]] can be reused directly. */
  final case class GameResult(
      history: List[(Move, GameState)],
      outcome: Outcome,
  )

  /** Aggregate of an N-game round. The win counts are from the
    * challenger's perspective so we can directly answer
    * "does the new weights snapshot beat the old?" without keeping
    * a separate accounting layer. */
  final case class RoundResult(
      games: Int,
      challengerWins: Int,
      championWins: Int,
      draws: Int,
      trainingRows: Vector[TrainingRow],
  )

  /** Play one game between `white` and `black`. Terminates on
    * checkmate / draw / no-legal-moves / `maxPlies` exhausted.
    *
    * `initialState` defaults to [[GameState.initial]] but can be
    * any legal position — useful for opening-diversified tournaments
    * (see [[OpeningPool]]) where each pair of games starts from a
    * different established opening to surface eval differences
    * that all-from-startpos play hides behind 90% draws. */
  def playGame(
      white: Search,
      black: Search,
      depth: Int,
      maxPlies: Int = 200,
      initialState: GameState = GameState.initial,
  ): UIO[GameResult] =
    loop(
      state    = initialState,
      history  = Vector.empty,
      ply      = 0,
      white    = white,
      black    = black,
      depth    = depth,
      maxPlies = maxPlies,
    )

  /** Run an N-game round: `champion` and `challenger` alternate
    * colors so neither benefits from the white-side advantage.
    *
    * When `parallelism > 1`, games run concurrently — each game is
    * independent (no shared mutable state at the GameState level),
    * but they share the supplied `Search` instances (so the
    * transposition table benefits from cross-game warming, and
    * the killer / history tables converge faster across the
    * round). The TT (`ConcurrentHashMap`) is thread-safe; killer
    * / history tables race-tolerate per the existing search-time
    * guarantees (corrupt reads = worse ordering, never wrong
    * moves).
    *
    * Outcomes are deterministic per game's individual moves but
    * the order of completion + TT contents are non-deterministic
    * — pass `parallelism = 1` (the default) when reproducibility
    * matters. */
  def round(
      champion: Search,
      challenger: Search,
      games: Int,
      depth: Int,
      maxPlies: Int = 200,
      parallelism: Int = 1,
      openingStates: Vector[GameState] = Vector.empty,
      collectTrainingRows: Boolean = true,
  ): UIO[RoundResult] =
    // Shared-instance behaviour (constant factories) — kept for
    // back-compat. At parallelism > 1 this races the shared search
    // state across games; use [[roundIsolated]] for clean
    // measurement. See its doc for why.
    roundIsolated(() => champion, () => challenger, games, depth,
      maxPlies, parallelism, openingStates, collectTrainingRows)

  /** Like [[round]] but mints a FRESH [[Search]] per game from the
    * supplied factories, so concurrent games never share mutable
    * search state (transposition table, killer / history / corrhist
    * tables). This is what makes `parallelism > 1` valid for Elo
    * *measurement*: with shared instances, the per-Search tables are
    * probed + mutated concurrently across the `foreachPar` games,
    * which destroys the color-swap mirror cancellation and makes
    * even identical-engine self-play swing ±200 Elo (the same flag
    * measured −424 at p=8 but +61 at p=1). Per-game instances make
    * each game independent, so identical engines score ~50% at any
    * parallelism.
    *
    * For a search backed by a shared external resource that cannot
    * be duplicated (e.g. a single Stockfish subprocess), have the
    * factory return that one instance — those games then serialise
    * on the resource's own lock instead of running truly parallel,
    * which is correct (just not faster). */
  def roundIsolated(
      championFactory: () => Search,
      challengerFactory: () => Search,
      games: Int,
      depth: Int,
      maxPlies: Int = 200,
      parallelism: Int = 1,
      openingStates: Vector[GameState] = Vector.empty,
      collectTrainingRows: Boolean = true,
  ): UIO[RoundResult] =
    if parallelism > 1 then
      parallelRound(championFactory, challengerFactory, games, depth, maxPlies, parallelism, openingStates, collectTrainingRows)
    else
      sequentialRound(championFactory, challengerFactory, games, depth, maxPlies, openingStates, collectTrainingRows)

  /** Pick the opening for the i-th game. Empty pool → start from
    * the standard initial position (back-compat default). Otherwise
    * round-robin: each opening drives a pair of games (one with
    * each colour pairing) so half-pairs see the same opening from
    * the opposite side. */
  private def openingFor(i: Int, pool: Vector[GameState]): GameState =
    if pool.isEmpty then GameState.initial
    else pool((i / 2) % pool.size)

  /** Sequential round — original behaviour. Kept as the default so
    * existing callers see no semantic change. */
  private def sequentialRound(
      championFactory: () => Search,
      challengerFactory: () => Search,
      games: Int,
      depth: Int,
      maxPlies: Int,
      openingStates: Vector[GameState],
      collectTrainingRows: Boolean,
  ): UIO[RoundResult] =
    ZIO.foldLeft(0 until games)(emptyRound) { (acc, i) =>
      val (whitePlayer, blackPlayer, challengerIsWhite) =
        gamePairing(i, championFactory, challengerFactory)
      playGame(
        whitePlayer, blackPlayer, depth, maxPlies,
        initialState = openingFor(i, openingStates),
      ).map { result =>
        val rows = if collectTrainingRows then gameToTrainingRows(result) else Vector.empty
        addToRound(acc, result.outcome, challengerIsWhite, rows)
      }
    }

  /** Parallel round — `ZIO.foreachPar` fans out games across
    * fibers with the supplied parallelism cap. Combination of
    * per-game `(result, challengerIsWhite, rows)` triples happens
    * once at the end via a sequential fold (cheap; just counter
    * + vector-concat operations). */
  private def parallelRound(
      championFactory: () => Search,
      challengerFactory: () => Search,
      games: Int,
      depth: Int,
      maxPlies: Int,
      parallelism: Int,
      openingStates: Vector[GameState],
      collectTrainingRows: Boolean,
  ): UIO[RoundResult] =
    // Fold each game's OUTCOME into a shared Ref as it finishes, via
    // foreachParDiscard (bounded by `parallelism`). The round retains nothing
    // per-game: not the game's full `history` (projected to its Outcome here)
    // nor a Chunk of all N results (the old `foreachPar.map(_.foldLeft)` held
    // all N tuples at once). Per-game Search/TT retention — the real OOM at
    // large N — was a separate leak in AlphaBetaSearch (a pooled inner-class
    // SearchBufs pinned its outer Search); fixed there. Counts are
    // commutative, so completion-order folding is exact.
    Ref.make(emptyRound).flatMap { ref =>
      ZIO
        .foreachParDiscard(0 until games) { i =>
          val (whitePlayer, blackPlayer, challengerIsWhite) =
            gamePairing(i, championFactory, challengerFactory)
          playGame(
            whitePlayer, blackPlayer, depth, maxPlies,
            initialState = openingFor(i, openingStates),
          ).flatMap { result =>
            val rows = if collectTrainingRows then gameToTrainingRows(result) else Vector.empty
            ref.update(addToRound(_, result.outcome, challengerIsWhite, rows))
          }
        }
        .withParallelism(parallelism)
        .zipRight(ref.get)
    }

  /** Color-alternation helper — shared by sequential + parallel
    * paths so the pairing rule lives in one place. Even-indexed
    * games put the challenger on white; odd ones swap.
    *
    * Mints a fresh search per side per game via the factories (see
    * [[roundIsolated]]). A constant factory (`() => sharedInstance`)
    * recovers the old shared-state behaviour. */
  private def gamePairing(
      i: Int,
      championFactory: () => Search,
      challengerFactory: () => Search,
  ): (Search, Search, Boolean) =
    val champion   = championFactory()
    val challenger = challengerFactory()
    if i % 2 == 0 then (challenger, champion, true)
    else (champion, challenger, false)

  private val emptyRound: RoundResult =
    RoundResult(0, 0, 0, 0, Vector.empty)

  /** Recursive game-loop core. */
  private def loop(
      state: GameState,
      history: Vector[(Move, GameState)],
      ply: Int,
      white: Search,
      black: Search,
      depth: Int,
      maxPlies: Int,
  ): UIO[GameResult] =
    if ply >= maxPlies then
      ZIO.succeed(GameResult(history.toList, Outcome.MaxMovesReached))
    else
      val activeSearch = if state.activeColor == Color.White then white else black
      activeSearch.bestMove(state, depth).flatMap {
        case None =>
          // No legal moves: terminal. In-check → opponent wins;
          // otherwise stalemate (draw).
          val outcome =
            if state.inCheck then
              if state.activeColor == Color.White then Outcome.BlackWins
              else Outcome.WhiteWins
            else Outcome.Draw
          ZIO.succeed(GameResult(history.toList, outcome))
        case Some(move) =>
          applyMoveSafe(state, move).flatMap { newState =>
            newState.status match
              case GameStatus.Checkmate(winner) =>
                val outcome =
                  if winner == Color.White then Outcome.WhiteWins
                  else Outcome.BlackWins
                ZIO.succeed(
                  GameResult((history :+ (move, newState)).toList, outcome)
                )
              case _: GameStatus.Draw =>
                ZIO.succeed(
                  GameResult((history :+ (move, newState)).toList, Outcome.Draw)
                )
              case GameStatus.Playing =>
                loop(newState, history :+ (move, newState), ply + 1,
                     white, black, depth, maxPlies)
              case _: GameStatus.Resignation =>
                // Resignations only happen on the Lichess bridge —
                // we'd never receive one in self-play. If we somehow
                // do, treat it like the player whose colour resigned
                // losing the game.
                val outcome =
                  if state.activeColor == Color.White then Outcome.BlackWins
                  else Outcome.WhiteWins
                ZIO.succeed(
                  GameResult((history :+ (move, newState)).toList, outcome)
                )
              case GameStatus.Timeout(winner) =>
                // Self-play games aren't timed, so a flag never happens here;
                // handled for exhaustiveness — the side still on the clock wins.
                val outcome =
                  if winner == Color.White then Outcome.WhiteWins
                  else Outcome.BlackWins
                ZIO.succeed(
                  GameResult((history :+ (move, newState)).toList, outcome)
                )
          }
      }

  /** Apply a search-recommended move. Failure here is a defect (the
    * search shouldn't propose illegal moves) — die rather than swallow. */
  private def applyMoveSafe(state: GameState, move: Move): UIO[GameState] =
    Game.applyMove(state, move).orDieWith(err =>
      new IllegalStateException(s"Self-play search proposed illegal move: ${err.message}")
    )

  /** Reuse [[PgnIngest.buildRows]] for the training-row construction:
    * synthesise a [[PgnParser.PgnGame]] from this game's history and
    * outcome, ignore the BookRows the helper also emits (they're not
    * useful in the self-play loop), and keep the TrainingRows. */
  private[train] def gameToTrainingRows(result: GameResult): Vector[TrainingRow] =
    val resultHeader = result.outcome match
      case Outcome.WhiteWins        => "1-0"
      case Outcome.BlackWins        => "0-1"
      case Outcome.Draw             => "1/2-1/2"
      case Outcome.MaxMovesReached  => "*"
    val synthetic = PgnParser.PgnGame(
      headers      = Map("Result" -> resultHeader),
      initialState = GameState.initial,
      history      = result.history,
    )
    val (_, trainingRows) = PgnIngest.buildRows(synthetic)
    trainingRows

  /** Fold the result of one game into a [[RoundResult]] accumulator. */
  private def addToRound(
      acc: RoundResult,
      outcome: Outcome,
      challengerIsWhite: Boolean,
      rows: Vector[TrainingRow],
  ): RoundResult =
    val (chWin, chmpWin, draw) = outcome match
      case Outcome.WhiteWins        =>
        if challengerIsWhite then (1, 0, 0) else (0, 1, 0)
      case Outcome.BlackWins        =>
        if challengerIsWhite then (0, 1, 0) else (1, 0, 0)
      case Outcome.Draw             => (0, 0, 1)
      // MaxMovesReached: no decisive result, count as a draw for
      // accounting (the challenge didn't beat the champion).
      case Outcome.MaxMovesReached  => (0, 0, 1)
    RoundResult(
      games          = acc.games + 1,
      challengerWins = acc.challengerWins + chWin,
      championWins   = acc.championWins   + chmpWin,
      draws          = acc.draws          + draw,
      trainingRows   = acc.trainingRows ++ rows,
    )

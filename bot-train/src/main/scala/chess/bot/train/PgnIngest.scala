package chess.bot.train

import zio.*

import chess.bot.data.{BookRepo, BookRow, TrainingRepo, TrainingRow}
import chess.bot.engine.FeatureExtractor
import chess.codec.PgnParser
import chess.codec.PgnParser.PgnGame
import chess.model.board.{GameState, Move}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.Zobrist

/** Streaming PGN-ingest pipeline.
  *
  * Drives the chess-bot.duckdb tables from a raw PGN corpus (Lichess
  * monthly dumps, KingBase, the user's own games, …). One pass per
  * input file: parse each game via [[chess.codec.PgnParser]], replay
  * the move list through the rules engine, then for every (pre-state,
  * move, post-state) triple:
  *   - emit a [[BookRow]] crediting the move with the eventual game
  *     result, weighted by the moving player's Elo
  *   - emit a [[TrainingRow]] with the side-to-move outcome and a
  *     "quiet" flag (true when the move wasn't a capture and the
  *     position isn't in check)
  *
  * Phase 4 ships the in-memory string ingest variant that powers the
  * test suite. Real file streaming (chunked, memory-bounded) is a
  * trivial wrapper on top — `ZStream.fromFile` + `splitGames` — that
  * we add when we wire the actual ingest job (Phase 6+).
  */
object PgnIngest:

  /** Counters reported back to the caller for the run. Useful as a
    * sanity check ("we said 5,000 games and saw 4,997 ingested") and
    * for logging progress when chained across files. */
  final case class Stats(
      games: Long,
      bookRows: Long,
      trainingRows: Long,
  ):
    def +(other: Stats): Stats =
      Stats(
        games        = games        + other.games,
        bookRows     = bookRows     + other.bookRows,
        trainingRows = trainingRows + other.trainingRows,
      )

  object Stats:
    val Zero: Stats = Stats(0L, 0L, 0L)

  /** Ingest one PGN game from a string. Returns [[Stats.Zero]] if the
    * parse fails (we log + skip rather than fail the whole run; bad
    * games are common in user-submitted PGN dumps and shouldn't take
    * the rest of the corpus with them). */
  def ingestOne(
      pgn: String,
      book: BookRepo,
      training: TrainingRepo,
      quality: Float = 1.0f,
  ): UIO[Stats] =
    PgnParser
      .parse(pgn)
      .foldZIO(
        err =>
          ZIO.logWarning(s"skipping malformed PGN: ${err.message}").as(Stats.Zero),
        game => ingestParsedGame(game, book, training, quality),
      )

  /** Ingest a multi-game PGN string. Games are separated by the
    * "[Event " header line; we use that as a structural marker rather
    * than a strict blank-line split — Lichess dumps mostly follow
    * that convention but occasionally have stray blank lines inside
    * single games.
    *
    * `quality` is the source-quality weight (0.0–1.0) attached to
    * every TrainingRow emitted for this corpus. See
    * [[CorpusSource]] for canonical values per source.
    */
  def ingestMany(
      pgn: String,
      book: BookRepo,
      training: TrainingRepo,
      quality: Float = 1.0f,
  ): UIO[Stats] =
    ZIO.foldLeft(splitGames(pgn))(Stats.Zero) { (acc, gamePgn) =>
      ingestOne(gamePgn, book, training, quality).map(acc + _)
    }

  /** Build the BookRows + TrainingRows for one parsed game and write
    * them in two batch operations. */
  private def ingestParsedGame(
      game: PgnGame,
      book: BookRepo,
      training: TrainingRepo,
      quality: Float,
  ): UIO[Stats] =
    val (bookRows, trainingRows) = buildRows(game, quality)
    for
      _ <- book.upsert(Chunk.fromIterable(bookRows))
      _ <- training.appendBatch(Chunk.fromIterable(trainingRows))
    yield Stats(
      games        = 1L,
      bookRows     = bookRows.size.toLong,
      trainingRows = trainingRows.size.toLong,
    )

  /** Pure conversion: PgnGame → (BookRows, TrainingRows). Walks the
    * (move, post-state) history while tracking the pre-state so each
    * row keys off the position the move was *made from*.
    *
    * `quality` stamps every TrainingRow with the corpus weight so
    * higher-quality sources (PGN Mentor) dominate the tuner's loss. */
  private[train] def buildRows(
      game: PgnGame,
      quality: Float = 1.0f,
  ): (Vector[BookRow], Vector[TrainingRow]) =
    val result   = game.headers.getOrElse("Result", "*")
    val whiteElo = game.headers.get("WhiteElo").flatMap(_.toIntOption).getOrElse(1500)
    val blackElo = game.headers.get("BlackElo").flatMap(_.toIntOption).getOrElse(1500)
    val bookBuf     = Vector.newBuilder[BookRow]
    val trainingBuf = Vector.newBuilder[TrainingRow]
    var preState    = game.initialState
    game.history.foreach { case (move, postState) =>
      val zobrist = Zobrist.hash(preState)
      val uci     = toUci(move)
      val mover   = preState.activeColor

      val (winsInc, drawsInc, lossesInc) = outcomeCounters(mover, result)
      val moverElo = if mover == Color.White then whiteElo else blackElo
      bookBuf += BookRow(
        zobrist  = zobrist,
        moveUci  = uci,
        wins     = winsInc,
        draws    = drawsInc,
        losses   = lossesInc,
        sumElo   = moverElo.toLong,
      )

      // Quiet heuristic: pre/post not in check AND the move wasn't a
      // capture. Texel tuning ignores tactical chaos.
      val isCapture =
        preState.board.contains(move.to) ||
          isEnPassantCapture(preState, move)
      val quiet = !preState.inCheck && !postState.inCheck && !isCapture
      // Pre-compute material features so the tuner can build its
      // Sample objects with one DB pass — no second FEN-parse round.
      val features = FeatureExtractor.material.features(preState)
      trainingBuf += TrainingRow(
        zobrist    = zobrist,
        outcome    = sideToMoveOutcome(mover, result),
        quiet      = quiet,
        weight     = quality,
        pawnDiff   = features("pawn"),
        knightDiff = features("knight"),
        bishopDiff = features("bishop"),
        rookDiff   = features("rook"),
        queenDiff  = features("queen"),
      )

      preState = postState
    }
    (bookBuf.result(), trainingBuf.result())

  /** Translate the PGN Result header into per-side counter increments
    * from the *moving side's* perspective:
    *   - moving side won  → wins += 1
    *   - draw             → draws += 1
    *   - moving side lost → losses += 1
    *   - "*" (unfinished) → no credit (returned as 0s; the row still
    *     records sumElo so it can be later weighted, but won't bias
    *     opening picks).
    */
  private def outcomeCounters(mover: Color, result: String): (Long, Long, Long) =
    result match
      case "1-0" if mover == Color.White => (1L, 0L, 0L)
      case "1-0"                         => (0L, 0L, 1L)
      case "0-1" if mover == Color.Black => (1L, 0L, 0L)
      case "0-1"                         => (0L, 0L, 1L)
      case "1/2-1/2"                     => (0L, 1L, 0L)
      case _                             => (0L, 0L, 0L)

  /** Texel-style outcome label, [0, 1] from the side-to-move perspective. */
  private def sideToMoveOutcome(mover: Color, result: String): Float =
    result match
      case "1-0" if mover == Color.White => 1.0f
      case "1-0"                         => 0.0f
      case "0-1" if mover == Color.Black => 1.0f
      case "0-1"                         => 0.0f
      case _                             => 0.5f

  /** True when `move` is an en-passant pawn capture from `state`. The
    * destination square isn't occupied (so `board.contains(to)` is
    * false) but it still counts as a capture for "is this quiet?". */
  private def isEnPassantCapture(state: GameState, move: Move): Boolean =
    state.board.get(move.from).exists(_.pieceType == PieceType.Pawn) &&
      move.from.col != move.to.col &&
      state.enPassantTarget.contains(move.to)

  /** UCI move serialisation (5 lines isn't worth depending on
    * bot-lichess just for the shared formatter). */
  private def toUci(move: Move): String =
    val base = s"${move.from.col}${move.from.row}${move.to.col}${move.to.row}"
    move.promotion match
      case Some(PieceType.Queen)  => base + "q"
      case Some(PieceType.Rook)   => base + "r"
      case Some(PieceType.Bishop) => base + "b"
      case Some(PieceType.Knight) => base + "n"
      case _                      => base

  /** Split a multi-game PGN string at every "[Event " marker. The
    * marker is the most reliable structural anchor in PGN — strict
    * blank-line delimiting breaks on dumps that have stray blank
    * lines inside annotated games. */
  private[train] def splitGames(pgn: String): List[String] =
    val tokens = pgn.split("(?m)^\\[Event ").toList
    tokens.tail.map(t => "[Event " + t).filter(_.trim.nonEmpty)

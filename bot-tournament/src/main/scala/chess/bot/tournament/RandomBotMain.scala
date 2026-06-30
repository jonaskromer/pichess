package chess.bot.tournament

import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.*

import chess.bot.tournament.TournamentRunner.Action
import chess.codec.UciCodec
import chess.model.board.{GameState, Move}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.MoveValidator

/** Throwaway tournament participant(s) that play RANDOM legal moves.
  *
  * Cheap (no search, ~zero CPU), local, committable, and a DISTINCT identity
  * from piChess — so a multi-bot tournament produces (a) games piChess isn't in,
  * exercising the opponent-archive path ([[TournamentImport.opponentSubmissions]]),
  * and (b) a non-trivial ladder for the history archive. Set `RANDOM_BOT_COUNT`
  * to spin up several distinct bots (`<name>-1`, `<name>-2`, …) in one JVM.
  *
  * Reuses [[TournamentApiClient]] (register/join/stream/move),
  * [[TournamentBridge.resolveOurColor]] (the gameStart self-filter), and
  * [[TournamentRunner.decide]] (position reconstruction from the game stream);
  * only the move CHOICE differs — uniform-random over legal moves, no search.
  *
  * Config via env: `TOURNAMENT_BASE_URL`, `TOURNAMENT_ID`, `TOURNAMENT_BOT_NAME`,
  * `RANDOM_BOT_COUNT`.
  */
object RandomBotMain extends ZIOAppDefault:

  override def run: ZIO[Any, Throwable, Unit] =
    for
      baseUrlStr <- ZIO.succeed(
        sys.env.getOrElse("TOURNAMENT_BASE_URL", "http://localhost:8086")
      )
      baseUrl <- ZIO
        .fromEither(Uri.parse(baseUrlStr))
        .mapError(e => new RuntimeException(s"bad TOURNAMENT_BASE_URL: $e"))
      name  = sys.env.getOrElse("TOURNAMENT_BOT_NAME", "random")
      tid   = sys.env.get("TOURNAMENT_ID").map(_.trim).filter(_.nonEmpty)
      count = sys.env.get("RANDOM_BOT_COUNT").flatMap(_.toIntOption).getOrElse(1)
      _ <- ZIO.scoped {
        HttpClientZioBackend.scoped().flatMap { backend =>
          ZIO.foreachParDiscard(1 to count) { i =>
            val botName = if count == 1 then name else s"$name-$i"
            for
              // Stagger registration/join — the in-memory server loses
              // concurrent joins (read-modify-write race).
              _ <- ZIO.sleep((i * 1500L).millis)
              api <- TournamentApiClient.sttp(
                backend,
                TournamentApiClient.Config(baseUrl)
              )
              reg <- api.register(botName)
              _   <- ZIO.logInfo(s"random-bot '$botName' registered as ${reg.id}")
              _   <- ZIO.foreachDiscard(tid)(playTournament(_, reg.id, api))
            yield ()
          }
        }
      }
    yield ()

  private def playTournament(
      tid: String,
      myId: String,
      api: TournamentApiClient
  ): IO[Throwable, Unit] =
    for
      _ <- api
        .joinTournament(tid)
        .catchAll(e => ZIO.logWarning(s"join $tid failed: ${e.getMessage}"))
      started <- Ref.make(Set.empty[String])
      _ <- api.streamTournament(tid).runForeach {
        case TournamentEvent.GameStart(_, gameId, _) =>
          TournamentBridge
            .resolveOurColor(tid, gameId, myId, started, api)
            .flatMap {
              case Some((color, _)) =>
                playGame(tid, gameId, color, api).forkDaemon.unit
              case None => ZIO.unit
            }
        case _ => ZIO.unit
      }
    yield ()

  private def playGame(
      tid: String,
      gameId: String,
      color: Color,
      api: TournamentApiClient
  ): IO[Throwable, Unit] =
    api
      .streamGame(tid, gameId)
      .runForeach { event =>
        TournamentRunner.decide(event, color) match
          case Action.MoveFrom(state, _, _) =>
            randomMove(state).flatMap {
              case Some(m) =>
                api
                  .makeMove(tid, gameId, UciCodec.serialize(m))
                  .catchAll(e =>
                    ZIO.logWarning(s"$gameId move failed: ${e.getMessage}")
                  )
              case None => ZIO.unit
            }
          case _ => ZIO.unit
      }
      .retry(Schedule.fixed(5.seconds))
      .catchAllCause(c =>
        ZIO.logErrorCause(s"random-bot game $gameId stopped", c)
      )

  /** Uniform-random pick over the legal moves of `state`; None if terminal. */
  private def randomMove(state: GameState): UIO[Option[Move]] =
    val moves = legalMoves(state)
    if moves.isEmpty then ZIO.none
    else Random.nextIntBounded(moves.size).map(i => Some(moves(i)))

  /** Legal moves for the side to move (queen promotions only — same
    * simplification as the engine's RulesAdapter). */
  private def legalMoves(state: GameState): List[Move] =
    val it  = MoveValidator.legalDestinationsIndexSync(state).iterator
    val buf = scala.collection.mutable.ListBuffer.empty[Move]
    while it.hasNext do
      val (from, destinations) = it.next()
      val isPawn = state.board.get(from).exists(_.pieceType == PieceType.Pawn)
      destinations.foreach { to =>
        val promo =
          if isPawn && (to.row == 1 || to.row == 8) then Some(PieceType.Queen)
          else None
        buf += Move(from, to, promo)
      }
    buf.toList

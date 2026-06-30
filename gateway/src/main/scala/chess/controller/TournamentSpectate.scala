package chess.controller

import pichess.game_service.ZioGameService
import pichess.game_service.{MoveRequest, NewGameRequest}
import zio.*
import zio.http.*
import zio.json.*

/** Spectate a NowChess tournament game on piChess's own board.
  *
  * `POST /tournament/{id}/game/{gameId}/spectate` returns a *mirror* game id in
  * our game-service; the browser then watches it via the existing
  * `/api/games/{mirrorId}/events` SSE feed + read-only board — same pipeline as
  * [[LichessSpectate]]. A daemon follows the tournament server's **public**
  * game snapshot (`GET /api/tournament/{id}/game/{gameId}` — no token) and
  * replays each new move into the mirror.
  *
  * Mirrors are **deduped per `(tournamentId, gameId)`** (one mirror, many
  * viewers), so a single follower drives the board and the gateway's
  * `SpectatorPresence` count on the mirror reflects everyone watching that
  * game.
  *
  * Coverage-excluded like the other external-I/O bridges (`LichessSpectate`):
  * it needs a live tournament server + game-service to exercise meaningfully.
  *
  * Limitation: the mirror starts from the standard position, so a tournament
  * with a custom/thematic start FEN would mirror incorrectly. NowChess
  * hardcodes the `standard` variant and defaults `startPosition` to standard,
  * so this is the common case; custom-start support (load the FEN first) can
  * come later.
  */
final class TournamentSpectate private (
    mirrors: Ref.Synchronized[Map[String, String]],
    presence: SpectatorPresence
):

  private val PollInterval = 1.second
  // Bound each snapshot poll so a slow/unreachable upstream can't hang the
  // follower (and pin a connection) — matches TournamentProxy's relay timeout.
  private val PollTimeout = 2.seconds
  private val MaxPolls = 1200 // ~20 min safety cap on a single game follow
  // Stop following once nobody has watched the mirror for this many consecutive
  // polls (≈ MaxIdlePolls × PollInterval). Leaves time for a viewer to
  // (re)connect the SSE after POST /spectate, then reaps the orphaned follower
  // + mirror so neither the polling nor the mirror map grows without bound.
  private val MaxIdlePolls = 30

  final case class StartResponse(mirrorId: String, orientation: String)
  private given JsonEncoder[StartResponse] =
    DeriveJsonEncoder.gen[StartResponse]

  /** Minimal projection of the public game snapshot. */
  private[controller] final case class Snapshot(
      moves: String,
      status: String
  )
  private given JsonDecoder[Snapshot] = DeriveJsonDecoder.gen[Snapshot]

  def routes(
      client: ZioGameService.GameServiceClient,
      tournamentBaseUrl: String
  ): Routes[Client, Response] =
    val base = tournamentBaseUrl.stripSuffix("/")
    Routes(
      Method.POST / "tournament" / string("id") / "game" / string(
        "gameId"
      ) / "spectate" ->
        handler { (id: String, gameId: String, _: Request) =>
          spectate(client, base, id, gameId).either.map {
            case Right(resp) => Response.json(resp.toJson)
            case Left(msg) =>
              Response
                .text(s"tournament spectate: $msg")
                .status(Status.BadGateway)
          }
        }
    )

  /** Reuse the existing mirror for this game, or create one (+ fork its
    * follower) under the lock so concurrent viewers share a single mirror.
    */
  private def spectate(
      client: ZioGameService.GameServiceClient,
      base: String,
      id: String,
      gameId: String
  ): ZIO[Client, String, StartResponse] =
    val key = s"$id/$gameId"
    mirrors.modifyZIO { current =>
      current.get(key) match
        case Some(mirrorId) =>
          ZIO.succeed((StartResponse(mirrorId, "white"), current))
        case None =>
          client
            .newGame(NewGameRequest())
            .mapError(e => s"mirror newGame failed: ${e.getStatus}")
            .flatMap { reply =>
              follow(client, base, id, gameId, reply.gameId).forkDaemon
                .as(
                  (
                    StartResponse(reply.gameId, "white"),
                    current.updated(key, reply.gameId)
                  )
                )
            }
    }

  private def snapshotUrl(
      base: String,
      id: String,
      gameId: String
  ): Either[String, URL] =
    URL.decode(s"$base/api/tournament/$id/game/$gameId").left.map(_.getMessage)

  /** Poll the public snapshot and replay new moves into the mirror until the
    * game ends (or the safety cap). Transient fetch errors just retry.
    */
  private def follow(
      client: ZioGameService.GameServiceClient,
      base: String,
      id: String,
      gameId: String,
      mirrorId: String
  ): ZIO[Client, Nothing, Unit] =
    val key = s"$id/$gameId"
    // Drop the mirror once the follower stops (game over, idle with no viewers,
    // a bad URL, the safety cap, or interruption) so a later spectate makes a
    // fresh one and the map doesn't grow without bound. Compare-and-remove so a
    // concurrent re-spectate that installed a new mirror under this key is left
    // intact.
    val cleanup =
      mirrors.update(m => if m.get(key).contains(mirrorId) then m - key else m)
    val run =
      snapshotUrl(base, id, gameId) match
        case Left(_) => ZIO.unit
        case Right(url) =>
          Ref.make(0).flatMap { applied =>
            def loop(n: Int, idleStreak: Int): ZIO[Client, Nothing, Unit] =
              if n <= 0 then ZIO.unit
              else
                fetchSnapshot(url).foldZIO(
                  _ => ZIO.sleep(PollInterval) *> loop(n - 1, idleStreak),
                  snap =>
                    val terminal = isTerminal(snap.status)
                    // Replay new moves onto the mirror board, then decide whether
                    // to keep following based on the game state + live viewers.
                    applyNew(client, mirrorId, snap.moves, applied) *>
                      presence.info(mirrorId).flatMap { info =>
                        TournamentSpectate.followStep(
                          info.count,
                          idleStreak,
                          terminal,
                          MaxIdlePolls
                        ) match
                          case None => ZIO.unit
                          case Some(next) =>
                            ZIO.sleep(PollInterval) *> loop(n - 1, next)
                      }
                )
            loop(MaxPolls, 0)
          }
    run.ensuring(cleanup)

  private[controller] def fetchSnapshot(
      url: URL,
      timeout: Duration = PollTimeout
  ): ZIO[Client, Throwable, Snapshot] =
    Client
      .batched(Request.get(url))
      .flatMap(_.body.asString)
      .flatMap(s =>
        ZIO
          .fromEither(s.fromJson[Snapshot])
          .mapError(e => new RuntimeException(s"snapshot decode: $e"))
      )
      // Don't let a slow/unreachable upstream hang the poll (and pin a
      // connection) — time out so the follower loop's existing retry kicks in.
      .timeoutFail(
        new RuntimeException(s"snapshot poll timed out after $timeout")
      )(timeout)

  /** Replay the moves not yet applied to the mirror (the snapshot's `moves` is
    * cumulative UCI). A rejected move is logged, not fatal.
    */
  private def applyNew(
      client: ZioGameService.GameServiceClient,
      mirrorId: String,
      moves: String,
      applied: Ref[Int]
  ): ZIO[Any, Nothing, Unit] =
    val toks =
      moves.split(' ').iterator.map(_.trim).filter(_.nonEmpty).toVector
    applied.get.flatMap { done =>
      TournamentSpectate
        .replayPending[io.grpc.StatusException](
          done,
          toks,
          uci => client.makeMove(MoveRequest(mirrorId, uci)).unit,
          (uci, e) =>
            ZIO.logWarning(
              s"mirror $mirrorId rejected move $uci: ${e.getStatus}"
            )
        )
        .flatMap(applied.set)
    }

  private def isTerminal(status: String): Boolean =
    status != "ongoing" && status != "pending"

object TournamentSpectate:
  def make(presence: SpectatorPresence): UIO[TournamentSpectate] =
    Ref.Synchronized
      .make(Map.empty[String, String])
      .map(new TournamentSpectate(_, presence))

  /** One follower step from the latest poll: given the mirror's live viewer
    * `count`, the running `idleStreak` (consecutive zero-viewer polls), whether
    * the snapshot is `terminal`, and the idle cap `maxIdlePolls` —
    *   - `None` → STOP following (the game ended, or nobody has watched for the
    *     whole grace window);
    *   - `Some(nextStreak)` → keep polling with the updated streak (reset to 0
    *     while anyone is watching).
    */
  private[controller] def followStep(
      count: Int,
      idleStreak: Int,
      terminal: Boolean,
      maxIdlePolls: Int
  ): Option[Int] =
    val nextStreak = if count <= 0 then idleStreak + 1 else 0
    if terminal || nextStreak >= maxIdlePolls then None
    else Some(nextStreak)

  /** Replay the cumulative `toks` past index `done` onto the mirror, applying
    * each via `apply` and returning the new applied count.
    *
    * On a rejected move we log via `onReject` and STOP, returning the index of
    * the last move that actually applied — we do NOT advance past the failure.
    * The follower retries from there on its next poll, so a transient failure
    * (e.g. a makeMove CPU-starved by a heavy bot think) self-heals instead of
    * permanently desyncing the mirror by skipping the move.
    */
  def replayPending[E](
      done: Int,
      toks: Vector[String],
      apply: String => IO[E, Unit],
      onReject: (String, E) => UIO[Unit]
  ): UIO[Int] =
    def go(idx: Int, rest: List[String]): UIO[Int] =
      rest match
        case Nil => ZIO.succeed(idx)
        case uci :: tail =>
          apply(uci).foldZIO(
            e => onReject(uci, e).as(idx), // stop; leave idx on the failed move
            _ => go(idx + 1, tail)
          )
    go(done, toks.drop(done).toList)

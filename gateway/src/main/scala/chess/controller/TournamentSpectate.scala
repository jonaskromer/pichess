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
    mirrors: Ref.Synchronized[Map[String, String]]
):

  private val PollInterval = 1.second
  private val MaxPolls = 1200 // ~20 min safety cap on a single game follow

  final case class StartResponse(mirrorId: String, orientation: String)
  private given JsonEncoder[StartResponse] =
    DeriveJsonEncoder.gen[StartResponse]

  /** Minimal projection of the public game snapshot. */
  private final case class Snapshot(moves: String, status: String)
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
    snapshotUrl(base, id, gameId) match
      case Left(_) => ZIO.unit
      case Right(url) =>
        Ref.make(0).flatMap { applied =>
          def loop(n: Int): ZIO[Client, Nothing, Unit] =
            if n <= 0 then ZIO.unit
            else
              fetchSnapshot(url).foldZIO(
                _ => ZIO.sleep(PollInterval) *> loop(n - 1),
                snap =>
                  applyNew(client, mirrorId, snap.moves, applied) *> {
                    if isTerminal(snap.status) then ZIO.unit
                    else ZIO.sleep(PollInterval) *> loop(n - 1)
                  }
              )
          loop(MaxPolls)
        }

  private def fetchSnapshot(url: URL): ZIO[Client, Throwable, Snapshot] =
    Client
      .batched(Request.get(url))
      .flatMap(_.body.asString)
      .flatMap(s =>
        ZIO
          .fromEither(s.fromJson[Snapshot])
          .mapError(e => new RuntimeException(s"snapshot decode: $e"))
      )

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
      ZIO.foreachDiscard(toks.drop(done)) { uci =>
        client
          .makeMove(MoveRequest(mirrorId, uci))
          .unit
          .catchAll(e =>
            ZIO.logWarning(
              s"mirror $mirrorId rejected move $uci: ${e.getStatus}"
            )
          )
      } *> applied.set(toks.size)
    }

  private def isTerminal(status: String): Boolean =
    status != "ongoing" && status != "pending"

object TournamentSpectate:
  def make: UIO[TournamentSpectate] =
    Ref.Synchronized
      .make(Map.empty[String, String])
      .map(new TournamentSpectate(_))

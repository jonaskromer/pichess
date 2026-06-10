package chess.controller

import scala.util.Random

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

import pichess.game_service.ZioGameService
import pichess.game_service.{MoveRequest, NewGameRequest}

/** Spectate a live Lichess bot-game on piChess's own board.
  *
  * `POST /lichess/games` challenges a random online Lichess bot to a
  * casual 5+3 game (our bot account, configured by `LICHESS_BOT_TOKEN`,
  * plays White), creates a *mirror* game in our own game-service, and
  * forks a daemon fiber that follows the public Lichess game stream and
  * replays every move into the mirror via `MakeMove`. The browser then
  * subscribes to the mirror's existing SSE feed
  * (`/api/games/{id}/events`) and renders it read-only — no Lichess
  * board embed, no token in the browser.
  *
  * The public stream (`/api/stream/game/{id}`) needs no auth, so the
  * mirror never competes with the bot process's own play-stream on
  * `/api/bot/game/stream/{id}`.
  */
object LichessSpectate:

  private val LichessBase = "https://lichess.org"

  /** What the browser gets back: the mirror game id to spectate plus
    * the orientation to show it from (our bot plays White). */
  final case class StartResponse(mirrorId: String, orientation: String)
  object StartResponse:
    given JsonEncoder[StartResponse] = DeriveJsonEncoder.gen[StartResponse]

  // -- Minimal decoders for the Lichess payloads we touch. All fields
  //    optional so unexpected line shapes decode to "nothing to do"
  //    rather than failing the stream.
  private final case class BotUser(username: Option[String], id: Option[String]):
    def name: Option[String] = username.orElse(id)
  private object BotUser:
    given JsonDecoder[BotUser] = DeriveJsonDecoder.gen[BotUser]

  private final case class IdHolder(id: String)
  private object IdHolder:
    given JsonDecoder[IdHolder] = DeriveJsonDecoder.gen[IdHolder]

  private final case class ChallengeResp(id: Option[String], challenge: Option[IdHolder]):
    def gameId: Option[String] = id.orElse(challenge.map(_.id))
  private object ChallengeResp:
    given JsonDecoder[ChallengeResp] = DeriveJsonDecoder.gen[ChallengeResp]

  /** A line from `/api/stream/game/{id}`. The first line is the full
    * game (carries cumulative UCI `moves`); subsequent lines carry the
    * latest move `lm` plus the new `fen`. */
  private final case class StreamLine(
      moves: Option[String],
      lm: Option[String],
      fen: Option[String],
  )
  private object StreamLine:
    given JsonDecoder[StreamLine] = DeriveJsonDecoder.gen[StreamLine]

  def routes(
      client: ZioGameService.GameServiceClient,
      token: String,
  ): Routes[Client, Response] =
    Routes(
      Method.POST / "lichess" / "games" -> handler { (_: Request) =>
        start(client, token).either.map {
          case Right(resp) =>
            Response.json(resp.toJson)
          case Left(msg) =>
            Response.text(s"lichess spectate: $msg").status(Status.BadGateway)
        }
      }
    )

  /** Orchestrate: pick opponent → challenge → mirror game → fork the
    * follower fiber → return the mirror id. */
  private def start(
      client: ZioGameService.GameServiceClient,
      token: String,
  ): ZIO[Client, String, StartResponse] =
    for
      bot      <- pickOnlineBot(token)
      lichessId <- challenge(token, bot)
      mirror   <- client
                    .newGame(NewGameRequest())
                    .mapError(e => s"mirror newGame failed: ${e.getStatus}")
      _        <- ZIO.logInfo(
                    s"Lichess spectate: challenged $bot (game $lichessId), mirror ${mirror.gameId}",
                  )
      _        <- follow(client, lichessId, mirror.gameId).forkDaemon
    yield StartResponse(mirror.gameId, "white")

  // -- Lichess HTTP helpers -------------------------------------------------

  private def authHeaders(token: String): Headers =
    Headers("Authorization", s"Bearer $token")

  private def liGet(token: String, path: String): ZIO[Client, String, String] =
    ZIO
      .fromEither(URL.decode(s"$LichessBase$path").left.map(_.getMessage))
      .flatMap { url =>
        Client
          .batched(Request(method = Method.GET, url = url, headers = authHeaders(token)))
          .flatMap(_.body.asString)
          .mapError(e => s"GET $path failed: ${e.getMessage}")
      }

  private def liPostForm(
      token: String,
      path: String,
      form: Map[String, String],
  ): ZIO[Client, String, String] =
    val encoded = form
      .map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }
      .mkString("&")
    ZIO
      .fromEither(URL.decode(s"$LichessBase$path").left.map(_.getMessage))
      .flatMap { url =>
        val req = Request(
          method = Method.POST,
          url = url,
          headers = authHeaders(token) ++
            Headers(Header.ContentType(MediaType.application.`x-www-form-urlencoded`)),
          body = Body.fromString(encoded),
        )
        Client
          .batched(req)
          .flatMap(_.body.asString)
          .mapError(e => s"POST $path failed: ${e.getMessage}")
      }

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

  /** Pick a random currently-online bot (never ourselves). */
  private def pickOnlineBot(token: String): ZIO[Client, String, String] =
    liGet(token, "/api/bot/online?nb=40").flatMap { body =>
      val names = body.linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap(line => line.fromJson[BotUser].toOption.flatMap(_.name))
        .filterNot(_.equalsIgnoreCase("pichess-htwg"))
        .toVector
      if names.isEmpty then ZIO.fail("no online bots available")
      else ZIO.succeed(names(Random.nextInt(names.size)))
    }

  /** Challenge `bot` to a casual 5+3 standard game; our bot plays White
    * so the mirror's default orientation matches our side. The returned
    * challenge id is also the game id once accepted. */
  private def challenge(token: String, bot: String): ZIO[Client, String, String] =
    liPostForm(
      token,
      s"/api/challenge/$bot",
      Map(
        "rated"           -> "false",
        "clock.limit"     -> "300",
        "clock.increment" -> "3",
        "color"           -> "white",
        "variant"         -> "standard",
      ),
    ).flatMap { body =>
      body.fromJson[ChallengeResp].toOption.flatMap(_.gameId) match
        case Some(id) => ZIO.succeed(id)
        case None     => ZIO.fail(s"challenge to $bot was not created: ${body.take(180)}")
    }

  // -- Mirror follower ------------------------------------------------------

  /** Follow the public Lichess game stream and replay each new move into
    * the mirror game. Retries the stream open until the opponent accepts
    * (the endpoint 404s until the game exists). */
  private def follow(
      client: ZioGameService.GameServiceClient,
      lichessId: String,
      mirrorId: String,
  ): ZIO[Client, Nothing, Unit] =
    val streamOnce =
      ZIO.scoped {
        for
          url  <- ZIO.fromEither(URL.decode(s"$LichessBase/api/stream/game/$lichessId").left.map(_.getMessage))
          resp <- Client.request(Request(method = Method.GET, url = url))
          _    <- ZIO.unless(resp.status.isSuccess)(ZIO.fail(s"stream not ready (${resp.status.code})"))
          appliedRef <- Ref.make(0)
          lastFenRef <- Ref.make("")
          _ <- resp.body.asStream
                 .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
                 .map(_.trim)
                 .filter(_.nonEmpty)
                 .runForeach(line => applyLine(client, mirrorId, line, appliedRef, lastFenRef))
        yield ()
      }
    // Up to ~90s of 1s retries while waiting for the opponent to accept,
    // then give up quietly. Any mid-stream error also retries within that
    // window so a transient drop reconnects.
    streamOnce
      .retry(Schedule.spaced(1.second) && Schedule.recurs(90))
      .catchAllCause(c => ZIO.logWarning(s"Lichess spectate follow ended for $lichessId: ${c.failureOption.getOrElse(c)}"))
      .unit

  /** Apply the moves implied by one stream line to the mirror game. */
  private def applyLine(
      client: ZioGameService.GameServiceClient,
      mirrorId: String,
      line: String,
      appliedRef: Ref[Int],
      lastFenRef: Ref[String],
  ): ZIO[Any, Nothing, Unit] =
    line.fromJson[StreamLine].toOption match
      case None => ZIO.unit
      case Some(sl) =>
        appliedRef.get.flatMap { applied =>
          sl.moves match
            // Full move list (first line): apply everything not yet applied.
            case Some(all) =>
              val toks = all.split(' ').iterator.map(_.trim).filter(_.nonEmpty).toVector
              val pending = toks.drop(applied)
              ZIO.foreachDiscard(pending)(applyMove(client, mirrorId, _)) *>
                appliedRef.set(toks.size) *>
                ZIO.foreachDiscard(sl.fen)(lastFenRef.set)
            // Incremental: a single new move, deduped by fen change.
            case None =>
              (sl.lm, sl.fen) match
                case (Some(uci), Some(fen)) =>
                  lastFenRef.get.flatMap { lastFen =>
                    ZIO.unless(fen == lastFen)(
                      applyMove(client, mirrorId, uci) *>
                        appliedRef.update(_ + 1) *>
                        lastFenRef.set(fen),
                    ).unit
                  }
                case _ => ZIO.unit
        }

  /** One mirror move. Errors are logged, not propagated — a single bad
    * move shouldn't tear down the whole follower (the spectate just
    * stops tracking; the real game continues on Lichess). */
  private def applyMove(
      client: ZioGameService.GameServiceClient,
      mirrorId: String,
      uci: String,
  ): ZIO[Any, Nothing, Unit] =
    client
      .makeMove(MoveRequest(mirrorId, uci))
      .unit
      .catchAll(e => ZIO.logWarning(s"mirror $mirrorId rejected move $uci: ${e.getStatus}"))

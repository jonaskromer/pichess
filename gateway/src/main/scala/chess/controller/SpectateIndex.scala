package chess.controller

import io.grpc.StatusException
import pichess.game_service.{ActiveGame, ListActiveGamesRequest, ZioGameService}
import zio.*
import zio.http.*
import zio.json.*

import chess.api.OngoingGame

/** The unified Spectate index: `GET /spectate/games` → the in-progress games to
  * watch, as a list of [[chess.api.OngoingGame]], fanned in from every source.
  *
  *   - **native** (4b-i): our game-service games (PvP / vs-bot) via
  *     `ListActiveGames`, joined with [[SpectatorPresence]] and the spectator
  *     rules (omit `allowSpectate=false`; full → listed-but-not-spectateable).
  *   - **tournament** (4b-ii): by default (`scope=ours`) only piChess's own
  *     tournament games — we first ask our bot service which tournaments it is
  *     in (`GET {botControl}/control/tournaments`, cheap + cluster-local) and
  *     skip the external NowChess server entirely when it is in none (the
  *     common idle/local-dev case). `scope=all` opts into the full fan-out
  *     across every started tournament — the costly external path that, against
  *     an unreachable server, would otherwise hang the request. Either way:
  *     started list → current-round pairings → per-game snapshot, keeping
  *     `status==ongoing`. Public (no host policy), so always spectateable; the
  *     browser spectates via a mirror (`tournamentId` is carried for that).
  *     Counts default to 0 (the shared mirror count is a later refinement).
  *   - **lichess** (4c): the bot account's own live games via Lichess `GET
  *     /api/account/playing` (needs the bot token). Public, mirror-spectated
  *     (`POST /lichess/games/{id}/spectate`), so always spectateable; counts
  *     default to 0 like the tournament source. Omitted entirely when no token
  *     is configured.
  *
  * Each source is fetched independently and a failing source is tolerated
  * (contributes nothing) so one outage never blanks the whole list.
  */
object SpectateIndex:

  private val LichessBase = "https://lichess.org"

  def routes(
      client: ZioGameService.GameServiceClient,
      presence: SpectatorPresence,
      tournamentBaseUrl: String,
      botControlUrl: String,
      botName: String,
      lichessToken: Option[String]
  ): Routes[Client, Response] =
    val base    = tournamentBaseUrl.stripSuffix("/")
    val botBase = botControlUrl.stripSuffix("/")
    // native + lichess are scope-independent (already only our own / local
    // games); only the tournament source varies with `scope`, so build it
    // per-request and keep the other two stable.
    val nativeSrc  = nativeGames(client, presence)
    val lichessSrc = lichessGames(LichessBase, lichessToken)
    Routes(
      Method.GET / "spectate" / "games" -> handler { (req: Request) =>
        // Default (`ours`): only piChess's own tournament games, asking our bot
        // service which tournaments it is in first (local) so the external
        // server is touched only when we are actually playing. `scope=all`
        // opts into the full external fan-out across every tournament.
        val allScope = req.url.queryParams.getAll("scope").contains("all")
        val tournamentSrc =
          if allScope then tournamentGames(base)
          else tournamentGamesScoped(base, botBase, botName)
        ZIO
          .foreachPar(List(nativeSrc, tournamentSrc, lichessSrc))(tolerate)
          .map(results => Response.json(results.flatten.toJson))
      }
    )

  /** Bound + tolerate one source: a failing, hanging, or defecting source
    * contributes nothing (logged), so the list still renders. The timeout is
    * the key fix — an unreachable tournament server (a configured URL that
    * never responds) would otherwise hang the whole request forever; foreachPar
    * runs the sources concurrently so one slow source can't delay the rest. */
  private def tolerate(
      source: ZIO[Client, Throwable, List[OngoingGame]]
  ): URIO[Client, List[OngoingGame]] =
    source
      .catchAllCause(c =>
        ZIO
          .logWarning(s"spectate source unavailable: ${c.squash.getMessage}")
          .as(Nil)
      )
      // `disconnect` so the 2s cap fires promptly: a stuck TCP connect to an
      // unreachable upstream interrupts slowly, and a plain `timeout` waits for
      // that interruption — `disconnect` moves it to the background so the
      // request always returns on time.
      .disconnect
      .timeoutTo(Nil)(identity)(2.seconds)

  // -- native (game-service) source ------------------------------------------

  private def nativeGames(
      client: ZioGameService.GameServiceClient,
      presence: SpectatorPresence
  ): IO[StatusException, List[OngoingGame]] =
    client.listActiveGames(ListActiveGamesRequest()).flatMap { reply =>
      ZIO
        .foreach(reply.games.toList) { g =>
          presence.info(g.gameId).map(info => toOngoingNative(g, info))
        }
        .map(_.flatten)
    }

  /** Apply the spectator rules to one native game. `None` ⇒ omit (host
    * disallowed spectating); otherwise an [[OngoingGame]] with the count/limit
    * and `spectateable` (false when full). Pure — the heart of the rules.
    */
  private[controller] def toOngoingNative(
      g: ActiveGame,
      info: SpectatorInfo
  ): Option[OngoingGame] =
    if info.policy.exists(!_.allowSpectate) then None
    else
      val limit = info.policy.map(_.limit).getOrElse(0)
      val spectateable = limit <= 0 || info.count < limit
      val (white, black) =
        if g.vsBot then
          if g.botSide == "white" then ("piChess (bot)", "Player")
          else ("Player", "piChess (bot)")
        else ("White", "Black")
      Some(
        OngoingGame(
          id = g.gameId,
          gameType = if g.vsBot then "pvbot" else "pvp",
          white = white,
          black = black,
          status = "ongoing",
          spectators = info.count,
          limit = limit,
          spectateable = spectateable,
          tournamentId = None
        )
      )

  // -- tournament (NowChess) source ------------------------------------------

  /** The default `scope=ours` source: only piChess's own tournament games. Ask
    * our bot service which tournaments it is in; if none, return immediately
    * with **no call to the external NowChess server** (the cheap path that
    * fixes the idle/local-dev hang). Otherwise fan out over only those
    * tournaments and keep games where a side is our bot (`botName`) — a
    * tournament round has many simultaneous games, but we want just ours.
    */
  private[controller] def tournamentGamesScoped(
      base: String,
      botBase: String,
      botName: String
  ): ZIO[Client, Throwable, List[OngoingGame]] =
    myTournamentIds(botBase).flatMap { tids =>
      if tids.isEmpty then ZIO.succeed(Nil)
      else
        ZIO
          .foreach(tids)(t => tournamentOngoing(base, t))
          .map(
            _.flatten.filter(g => g.white == botName || g.black == botName)
          )
    }

  /** The tournament ids our bot is currently playing, from its cluster-internal
    * control API (`GET {botBase}/control/tournaments → {"active":[...]}`).
    */
  private def myTournamentIds(
      botBase: String
  ): ZIO[Client, Throwable, List[String]] =
    getJson[ActiveTournaments](botBase, "/control/tournaments", Headers.empty)
      .map(_.active)

  /** Ongoing games across every started NowChess tournament (the `scope=all`
    * source): started list → each tournament's current-round pairings →
    * per-game snapshot, kept when `status==ongoing`. Public (no host policy),
    * so always spectateable.
    */
  private[controller] def tournamentGames(
      base: String
  ): ZIO[Client, Throwable, List[OngoingGame]] =
    getJson[StartedList](base, "/api/tournament", Headers.empty).flatMap {
      list =>
        ZIO
          .foreach(list.started)(t => tournamentOngoing(base, t.id))
          .map(_.flatten)
    }

  private def tournamentOngoing(
      base: String,
      tid: String
  ): ZIO[Client, Throwable, List[OngoingGame]] =
    for
      tour <- getJson[TournamentRound](
        base,
        s"/api/tournament/$tid",
        Headers.empty
      )
      round <- getJson[RoundPairings](
        base,
        s"/api/tournament/$tid/round/${tour.round}",
        Headers.empty
      )
      ids = round.pairings.flatMap(_.matches.map(_.gameId))
      games <- ZIO
        .foreach(ids)(gid => gameIfOngoing(base, tid, gid))
        .map(_.flatten)
    yield games

  private def gameIfOngoing(
      base: String,
      tid: String,
      gid: String
  ): ZIO[Client, Throwable, Option[OngoingGame]] =
    getJson[GameSnap](base, s"/api/tournament/$tid/game/$gid", Headers.empty)
      .map { snap =>
        Option.when(snap.status == "ongoing")(
          OngoingGame(
            id = gid,
            gameType = "tournament",
            white = snap.white.name,
            black = snap.black.name,
            status = "ongoing",
            spectators = 0,
            limit = 0,
            spectateable = true,
            tournamentId = Some(tid)
          )
        )
      }

  // -- lichess (Lichess account) source --------------------------------------

  /** The bot account's own live games from `GET /api/account/playing`. `None`
    * token ⇒ no Lichess configured ⇒ no rows (no I/O). Public/uncapped, so each
    * is spectateable; the browser opens a mirror via `POST
    * /lichess/games/{id}/spectate`.
    */
  private[controller] def lichessGames(
      base: String,
      token: Option[String]
  ): ZIO[Client, Throwable, List[OngoingGame]] =
    token match
      case None => ZIO.succeed(Nil)
      case Some(tok) =>
        getJson[NowPlaying](
          base,
          "/api/account/playing",
          Headers("Authorization", s"Bearer $tok")
        ).map(_.nowPlaying.map(toOngoingLichess))

  /** Project one `nowPlaying` entry. The bot is whichever side `color` names;
    * the opponent is its Lichess username (falling back to id, then a generic
    * label). Pure.
    */
  private[controller] def toOngoingLichess(g: LiNowPlaying): OngoingGame =
    val me = "piChess (bot)"
    val opponent =
      g.opponent.username.orElse(g.opponent.id).getOrElse("opponent")
    val (white, black) =
      if g.color == "white" then (me, opponent) else (opponent, me)
    OngoingGame(
      id = g.gameId,
      gameType = "lichess",
      white = white,
      black = black,
      status = "ongoing",
      spectators = 0,
      limit = 0,
      spectateable = true,
      tournamentId = None
    )

  private def getJson[A: JsonDecoder](
      base: String,
      path: String,
      headers: Headers
  ): ZIO[Client, Throwable, A] =
    ZIO
      .fromEither(URL.decode(s"$base$path"))
      .flatMap(u =>
        Client
          .batched(Request.get(u).addHeaders(headers))
          .flatMap(_.body.asString)
      )
      .flatMap(s =>
        ZIO
          .fromEither(s.fromJson[A])
          .mapError(e => new RuntimeException(s"decode $path: $e"))
      )

  // Minimal projections of the public NowChess JSON (extra fields ignored).
  private final case class TidRef(id: String)
  private given JsonDecoder[TidRef] = DeriveJsonDecoder.gen[TidRef]
  private final case class StartedList(started: List[TidRef])
  private given JsonDecoder[StartedList] = DeriveJsonDecoder.gen[StartedList]
  // Our bot's control API: { "active": ["t1", ...] }.
  private final case class ActiveTournaments(active: List[String])
  private given JsonDecoder[ActiveTournaments] =
    DeriveJsonDecoder.gen[ActiveTournaments]
  private final case class TournamentRound(round: Int)
  private given JsonDecoder[TournamentRound] =
    DeriveJsonDecoder.gen[TournamentRound]
  private final case class MatchRef(gameId: String)
  private given JsonDecoder[MatchRef] = DeriveJsonDecoder.gen[MatchRef]
  private final case class PairingMatches(matches: List[MatchRef])
  private given JsonDecoder[PairingMatches] =
    DeriveJsonDecoder.gen[PairingMatches]
  private final case class RoundPairings(pairings: List[PairingMatches])
  private given JsonDecoder[RoundPairings] =
    DeriveJsonDecoder.gen[RoundPairings]
  private final case class NameRef(name: String)
  private given JsonDecoder[NameRef] = DeriveJsonDecoder.gen[NameRef]
  private final case class GameSnap(
      status: String,
      white: NameRef,
      black: NameRef
  )
  private given JsonDecoder[GameSnap] = DeriveJsonDecoder.gen[GameSnap]

  // Minimal projection of the Lichess `GET /api/account/playing` payload.
  private[controller] final case class LiOpponent(
      username: Option[String],
      id: Option[String]
  )
  private given JsonDecoder[LiOpponent] = DeriveJsonDecoder.gen[LiOpponent]
  private[controller] final case class LiNowPlaying(
      gameId: String,
      color: String,
      opponent: LiOpponent
  )
  private given JsonDecoder[LiNowPlaying] =
    DeriveJsonDecoder.gen[LiNowPlaying]
  private final case class NowPlaying(nowPlaying: List[LiNowPlaying])
  private given JsonDecoder[NowPlaying] = DeriveJsonDecoder.gen[NowPlaying]

package chess.bot.tournament

import zio.json.*

import chess.model.piece.Color

/** Discriminated unions modelling the NDJSON events the NowChess tournament
  * server (`maichess/tournament-server`) emits.
  *
  * Two streams (see `docs/tournament-integration.md`):
  *   - the tournament stream (`GET /api/tournament/{id}/stream`) emits
  *     [[TournamentEvent]] — round lifecycle + per-game `gameStart`.
  *   - the per-game stream (`GET /api/tournament/{id}/game/{gameId}/stream`)
  *     emits [[GameEvent]] — an initial state snapshot then one event per move
  *     / status change.
  *
  * Unlike the Lichess Bot API (nested `gameFull { state }`), NowChess encodes
  * every event **flat** with a `"type"` discriminator at the top level —
  * verified against the server's `JsonCodecs.scala`. zio-json's
  * `@jsonDiscriminator("type")` + `@jsonHint` matches that exactly.
  *
  * Two contract facts the decoders below pin:
  *   - the per-game stream carries **no colour** — and the tournament stream's
  *     `gameStart` `color` is **not** authoritative either (it's broadcast for
  *     both colours of every game), so the bridge resolves our colour by
  *     matching our id against the game's players (see
  *     [[TournamentBridge.resolveOurColor]]);
  *   - clocks are **seconds** ([[GameClock]] is `Double`). The updated server
  *     also carries `increment` on every clock object, but we ignore it there
  *     and read the increment from the tournament [[TournamentClock]] instead.
  */

// `Color` lives in the pure domain with no JSON codec; the wire form is the
// lowercase string "white"/"black". Defined top-level so the derived event
// codecs in this file see it during derivation.
given JsonDecoder[Color] = JsonDecoder[String].mapOrFail:
  case "white" => Right(Color.White)
  case "black" => Right(Color.Black)
  case other   => Left(s"invalid color: $other")

given JsonEncoder[Color] = JsonEncoder[String].contramap:
  case Color.White => "white"
  case Color.Black => "black"

/** Tournament time control: base + increment, in **seconds**. Read from `GET
  * /api/tournament/{id}` (`clock`); the increment is NOT present in the
  * per-game events, so it must be carried from here.
  */
final case class TournamentClock(limit: Int, increment: Int)
object TournamentClock:
  given JsonDecoder[TournamentClock] = DeriveJsonDecoder.gen[TournamentClock]
  given JsonEncoder[TournamentClock] = DeriveJsonEncoder.gen[TournamentClock]

/** Per-game clock snapshot: each side's remaining time in **seconds**
  * (fractional). The server also sends an `increment` field on the clock
  * object; we deliberately omit it here (zio-json ignores the extra field) and
  * source the increment from the tournament [[TournamentClock]].
  */
final case class GameClock(whiteTime: Double, blackTime: Double)
object GameClock:
  given JsonDecoder[GameClock] = DeriveJsonDecoder.gen[GameClock]
  given JsonEncoder[GameClock] = DeriveJsonEncoder.gen[GameClock]

/** A bot's identity as it appears in tournament/game payloads. */
final case class BotRef(id: String, name: String)
object BotRef:
  given JsonDecoder[BotRef] = DeriveJsonDecoder.gen[BotRef]
  given JsonEncoder[BotRef] = DeriveJsonEncoder.gen[BotRef]

// ── Tournament-level events ───────────────────────────────────────────

@jsonDiscriminator("type")
sealed trait TournamentEvent

object TournamentEvent:

  /** Round 1 pairings have been computed; games are about to start. */
  @jsonHint("tournamentStarted")
  case object TournamentStarted extends TournamentEvent

  /** A new round began. */
  @jsonHint("roundStarted")
  final case class RoundStarted(round: Int) extends TournamentEvent

  /** A game in the round has started. NOTE: `gameStart` is broadcast for BOTH
    * colours of EVERY game to every subscriber, so `color` here is **not**
    * authoritative — the bridge ignores it and works out whether this is our
    * game (and as which colour) via [[TournamentBridge.resolveOurColor]].
    */
  @jsonHint("gameStart")
  final case class GameStart(round: Int, gameId: String, color: Color)
      extends TournamentEvent

  /** All games in a round finished. */
  @jsonHint("roundFinished")
  final case class RoundFinished(round: Int) extends TournamentEvent

  /** The tournament is over; `winner` is the champion. Terminates the
    * tournament stream.
    */
  @jsonHint("tournamentFinished")
  final case class TournamentFinished(winner: BotRef) extends TournamentEvent

  /** Keep-alive line the server interleaves every ~10s (`NdjsonStream`). A real
    * JSON object, not a blank line — we decode it so it doesn't fail the
    * stream, then ignore it.
    */
  @jsonHint("heartbeat")
  case object Heartbeat extends TournamentEvent

  given JsonDecoder[TournamentEvent] = DeriveJsonDecoder.gen[TournamentEvent]
  given JsonEncoder[TournamentEvent] = DeriveJsonEncoder.gen[TournamentEvent]

// ── Per-game events ───────────────────────────────────────────────────

@jsonDiscriminator("type")
sealed trait GameEvent

object GameEvent:

  /** The first event on every game stream (and on every reconnect): a full
    * state snapshot. `fen` is the authoritative current position, so no move
    * replay is needed. `status` is one of `pending` / `ongoing` / `checkmate` /
    * `stalemate` / `draw` / `resigned` / `timeout`.
    */
  @jsonHint("gameState")
  final case class StateSnapshot(
      fen: String,
      moves: String,
      turn: Color,
      clock: GameClock,
      status: String,
      winner: Option[Color]
  ) extends GameEvent

  /** A move was played (ours or the opponent's). `fen`/`turn` reflect the
    * position AFTER the move; there is no `status` field — termination comes
    * via [[GameEnded]].
    */
  @jsonHint("move")
  final case class MovePlayed(
      uci: String,
      fen: String,
      turn: Color,
      clock: GameClock
  ) extends GameEvent

  /** The game ended. `winner` is `None` on a draw. Closes the game stream. */
  @jsonHint("gameEnd")
  final case class GameEnded(
      winner: Option[Color],
      status: String
  ) extends GameEvent

  /** Keep-alive line the server interleaves every ~10s (`NdjsonStream`) so idle
    * game streams don't fail our line-by-line decoder. Ignored by the runner.
    */
  @jsonHint("heartbeat")
  case object Heartbeat extends GameEvent

  given JsonDecoder[GameEvent] = DeriveJsonDecoder.gen[GameEvent]
  given JsonEncoder[GameEvent] = DeriveJsonEncoder.gen[GameEvent]

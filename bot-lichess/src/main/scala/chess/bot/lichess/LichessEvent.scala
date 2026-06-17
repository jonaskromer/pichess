package chess.bot.lichess

import zio.json.*

/** Discriminated unions modelling the events the Lichess Bot API emits.
  *
  * Two streams:
  *   - the account-level stream (`GET /api/stream/event`) emits
  *     [[AccountEvent]] envelopes — challenges and game lifecycle.
  *   - the per-game stream (`GET /api/bot/game/stream/{gameId}`) emits
  *     [[GameEvent]] envelopes — initial game state plus subsequent state
  *     updates and chat lines.
  *
  * Lichess uses a `"type": "..."` discriminator. zio-json's
  * `@jsonDiscriminator` matches that shape directly so the JSON parses straight
  * into these ADTs without a custom decoder.
  */

// ── Account-level events ──────────────────────────────────────────────

@jsonDiscriminator("type")
sealed trait AccountEvent

object AccountEvent:

  /** Sent on every game state change for an active game — used to track when
    * our turn rolls around, when the game ends, etc. We only need the id to
    * subscribe to the per-game stream.
    */
  @jsonHint("gameStart")
  final case class GameStart(game: GameRef) extends AccountEvent

  /** Sent once when a game finishes (any reason). Triggers cleanup of the
    * per-game fiber.
    */
  @jsonHint("gameFinish")
  final case class GameFinish(game: GameRef) extends AccountEvent

  /** Incoming challenge — the bot decides whether to accept based on its policy
    * (variant, time control, rating range, …).
    */
  @jsonHint("challenge")
  final case class Challenge(challenge: ChallengeInfo) extends AccountEvent

  /** Sender cancelled or we declined — surface for logging only. */
  @jsonHint("challengeCanceled")
  final case class ChallengeCanceled(challenge: ChallengeInfo)
      extends AccountEvent

  @jsonHint("challengeDeclined")
  final case class ChallengeDeclined(challenge: ChallengeInfo)
      extends AccountEvent

  given JsonDecoder[AccountEvent] = DeriveJsonDecoder.gen[AccountEvent]
  given JsonEncoder[AccountEvent] = DeriveJsonEncoder.gen[AccountEvent]

/** Minimal game-identifying payload that arrives nested under the `game` field
  * of [[AccountEvent.GameStart]] / [[AccountEvent.GameFinish]]. We only need
  * the id to hop to the per-game stream.
  */
final case class GameRef(id: String)

object GameRef:
  given JsonDecoder[GameRef] = DeriveJsonDecoder.gen[GameRef]
  given JsonEncoder[GameRef] = DeriveJsonEncoder.gen[GameRef]

/** Challenge payload. `variant.key == "standard"` is the only variant our bot
  * accepts in Phase 2 — everything else (chess960, atomic, antichess, …) gets
  * declined to keep the scope tight.
  */
final case class ChallengeInfo(
    id: String,
    rated: Boolean,
    variant: VariantRef,
    speed: String,
    timeControl: TimeControlRef,
    challenger: PlayerRef
)

object ChallengeInfo:
  given JsonDecoder[ChallengeInfo] = DeriveJsonDecoder.gen[ChallengeInfo]
  given JsonEncoder[ChallengeInfo] = DeriveJsonEncoder.gen[ChallengeInfo]

final case class VariantRef(key: String)
object VariantRef:
  given JsonDecoder[VariantRef] = DeriveJsonDecoder.gen[VariantRef]
  given JsonEncoder[VariantRef] = DeriveJsonEncoder.gen[VariantRef]

final case class TimeControlRef(`type`: String)
object TimeControlRef:
  given JsonDecoder[TimeControlRef] = DeriveJsonDecoder.gen[TimeControlRef]
  given JsonEncoder[TimeControlRef] = DeriveJsonEncoder.gen[TimeControlRef]

final case class PlayerRef(id: Option[String], name: Option[String])
object PlayerRef:
  given JsonDecoder[PlayerRef] = DeriveJsonDecoder.gen[PlayerRef]
  given JsonEncoder[PlayerRef] = DeriveJsonEncoder.gen[PlayerRef]

// ── Per-game events ───────────────────────────────────────────────────

@jsonDiscriminator("type")
sealed trait GameEvent

object GameEvent:

  /** The very first event on every per-game stream: full game header + current
    * state. `initialFen == "startpos"` for the standard starting position; any
    * other value is a FEN to parse. `moves` is the space-separated UCI history
    * (may be empty for a fresh game).
    *
    * `state` is nested rather than flattened because Lichess emits the
    * GameState as a sub-document. We mirror that shape so the decoder stays
    * straightforward.
    */
  @jsonHint("gameFull")
  final case class GameFull(
      id: String,
      initialFen: String,
      white: PlayerRef,
      black: PlayerRef,
      state: GameStateUpdate
  ) extends GameEvent

  /** State update on every move (ours or theirs). `moves` is the full UCI
    * history (cumulative — Lichess re-sends the whole list each tick). `status`
    * carries the game lifecycle ("started", "mate", "draw", "resign", …).
    */
  @jsonHint("gameState")
  final case class GameStateEvent(
      moves: String,
      wtime: Long,
      btime: Long,
      winc: Long,
      binc: Long,
      status: String
  ) extends GameEvent

  /** Chat message. We log + ignore in Phase 2. */
  @jsonHint("chatLine")
  final case class ChatLine(room: String, username: String, text: String)
      extends GameEvent

  /** Opponent disconnected; Lichess auto-pauses the game. No action needed on
    * our side.
    */
  @jsonHint("opponentGone")
  final case class OpponentGone(gone: Boolean, claimWinInSeconds: Option[Int])
      extends GameEvent

  given JsonDecoder[GameEvent] = DeriveJsonDecoder.gen[GameEvent]
  given JsonEncoder[GameEvent] = DeriveJsonEncoder.gen[GameEvent]

/** Nested inside [[GameEvent.GameFull.state]]. Same shape as the top-level
  * [[GameEvent.GameStateEvent]] but discriminator-less since it's never the
  * root of an event envelope.
  */
final case class GameStateUpdate(
    moves: String,
    wtime: Long,
    btime: Long,
    winc: Long,
    binc: Long,
    status: String
)

object GameStateUpdate:
  given JsonDecoder[GameStateUpdate] = DeriveJsonDecoder.gen[GameStateUpdate]
  given JsonEncoder[GameStateUpdate] = DeriveJsonEncoder.gen[GameStateUpdate]

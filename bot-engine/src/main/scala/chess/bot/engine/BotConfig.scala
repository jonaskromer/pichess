package chess.bot.engine

import zio.json.*

import chess.model.piece.Color

/** Configuration for a single vs-bot game.
  *
  * Captures the three settings the user sees on the new-game form:
  *
  *   - [[botSide]]:   which colour the bot plays. The player gets
  *     the other side. White moves first by chess convention; if the
  *     bot is white it plays the opening move immediately.
  *   - [[allowUndo]]: whether the player is allowed to take back a
  *     move pair (their move + the bot's reply). Casual play wants
  *     this on; rated / training games want it off.
  *   - [[difficulty]]: search-depth / noise level. See
  *     [[Difficulty]] for the per-level details.
  *
  * Lives in `bot-engine` (not `bot-data` or `api`) because the engine
  * is the lowest layer that needs to read all three fields — the
  * gateway and game-service compose this config from their own DTO
  * shapes. zio-json codecs travel along for free.
  */
final case class BotConfig(
    botSide: Color,
    difficulty: Difficulty,
    allowUndo: Boolean,
)

object BotConfig:

  // Color lives in `domain` without a JSON codec — provide one locally
  // (lower-case string: "white" / "black") so BotConfig can derive.
  // Kept private so it doesn't accidentally collide with codecs the
  // gateway / api modules may want to define on their own terms.
  private given JsonEncoder[Color] = JsonEncoder[String].contramap {
    case Color.White => "white"
    case Color.Black => "black"
  }
  private given JsonDecoder[Color] = JsonDecoder[String].mapOrFail {
    case s if s.equalsIgnoreCase("white") => Right(Color.White)
    case s if s.equalsIgnoreCase("black") => Right(Color.Black)
    case other                            => Left(s"Unknown color: '$other'")
  }

  given JsonEncoder[BotConfig] = DeriveJsonEncoder.gen[BotConfig]
  given JsonDecoder[BotConfig] = DeriveJsonDecoder.gen[BotConfig]

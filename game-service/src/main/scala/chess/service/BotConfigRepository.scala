package chess.service

import zio.*

import chess.bot.engine.BotConfig
import chess.model.GameId

/** Storage for per-game bot configuration in vs-bot games.
  *
  * Kept as a tiny separate concern (rather than folded into
  * [[chess.persistence.GameRepository]]) because:
  *   - bot-mode games are a minority; mainstream PvP doesn't need
  *     the column / lookup
  *   - bot config is immutable for the game's lifetime — once set on
  *     newGameVsBot, never updated — so it doesn't share the
  *     update-frequency profile of GameState
  *   - we want to swap implementations cleanly (in-memory for v1,
  *     Mongo/Redis later) without touching GameRepository's contract.
  *
  * The in-memory implementation is fine for a session-scoped MVP;
  * vs-bot games started in one server boot become regular PvP games
  * after a restart (the bot config is lost, and the game-service no
  * longer auto-responds for the bot side). That's acceptable for
  * the first iteration — promotion to durable storage is a one-file
  * swap when the time comes.
  */
trait BotConfigRepository:
  def save(id: GameId, config: BotConfig): UIO[Unit]
  def get(id: GameId): UIO[Option[BotConfig]]
  def delete(id: GameId): UIO[Unit]

object BotConfigRepository:

  /** In-memory `Ref[Map]` implementation. Lifetime tied to the
    * surrounding [[ZLayer]] scope. */
  val inMemoryLayer: ULayer[BotConfigRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[GameId, BotConfig]).map(new InMemory(_))
    )

  /** Test/factory helper — give back the bare repo without a layer. */
  def inMemory: UIO[BotConfigRepository] =
    Ref.make(Map.empty[GameId, BotConfig]).map(new InMemory(_))

  private final class InMemory(state: Ref[Map[GameId, BotConfig]])
      extends BotConfigRepository:
    def save(id: GameId, config: BotConfig): UIO[Unit] =
      state.update(_ + (id -> config))
    def get(id: GameId): UIO[Option[BotConfig]] =
      state.get.map(_.get(id))
    def delete(id: GameId): UIO[Unit] =
      state.update(_ - id)

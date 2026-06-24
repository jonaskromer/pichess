package chess.api

import zio.json.*

/** One row in the unified Spectate list — an in-progress game of any source.
  *
  *   - `gameType`: `"pvp"` | `"pvbot"` | `"tournament"` | `"lichess"`.
  *   - `id`: the game-service id for native (pvp/pvbot) games — spectate them
  *     directly via `/api/games/{id}/events`; for `tournament` games it's the
  *     NowChess gameId (spectate via `POST
  *     /tournament/{tournamentId}/game/{id}/spectate`); for `lichess` games it's
  *     the Lichess gameId (spectate via `POST /lichess/games/{id}/spectate`).
  *   - `spectators` / `limit`: current viewers and the cap (`0` = unlimited).
  *   - `spectateable`: false when full (the row is still listed, button
  *     disabled). Games whose host disallowed spectating are omitted from the
  *     list entirely.
  *   - `tournamentId`: set for `tournament` games (needed to open the mirror).
  */
final case class OngoingGame(
    id: String,
    gameType: String,
    white: String,
    black: String,
    status: String,
    spectators: Int,
    limit: Int,
    spectateable: Boolean,
    tournamentId: Option[String]
)

object OngoingGame:
  given JsonEncoder[OngoingGame] = DeriveJsonEncoder.gen[OngoingGame]
  given JsonDecoder[OngoingGame] = DeriveJsonDecoder.gen[OngoingGame]

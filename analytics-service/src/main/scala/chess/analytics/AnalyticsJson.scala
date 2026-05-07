package chess.analytics

import zio.json.*

object AnalyticsJson:

  final case class TopMove(san: String, plays: Long)
  object TopMove:
    given JsonCodec[TopMove] = DeriveJsonCodec.gen[TopMove]

  final case class TopMovesResponse(moves: List[TopMove])
  object TopMovesResponse:
    given JsonCodec[TopMovesResponse] = DeriveJsonCodec.gen[TopMovesResponse]

  final case class AverageGameLengthResponse(plies: Option[Double])
  object AverageGameLengthResponse:
    given JsonCodec[AverageGameLengthResponse] =
      DeriveJsonCodec.gen[AverageGameLengthResponse]

  final case class GameCountResponse(games: Long)
  object GameCountResponse:
    given JsonCodec[GameCountResponse] =
      DeriveJsonCodec.gen[GameCountResponse]

package chess.bot.lichess

import zio.*
import zio.json.*

import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.model.Uri

import chess.bot.engine.Search
import chess.codec.FenSerializer
import chess.model.board.{GameState, Move}

/** Tablebase oracle backed by the Lichess 7-piece Syzygy HTTP API
  * ([[https://tablebase.lichess.ovh]]) — roadmap #8.
  *
  * Used as the `tb` [[Search]] inside [[chess.bot.engine.TbAugmentedSearch]]:
  * once an endgame simplifies to ≤ 7 pieces, ONE probe returns the
  * tablebase-PERFECT move (respecting the 50-move rule) instead of a heuristic
  * search. This gives perfect endgame play with zero local tablebase files
  * (Syzygy is ~1 GB for 5-piece, ~150 GB for 6) and no Stockfish dependency.
  *
  * Fail-safe by construction: any failure — network error, timeout, JSON
  * parse, or a position the API doesn't cover — yields `None`, so the caller
  * transparently falls back to the normal search. The bot never blocks on or
  * breaks because of the external service. */
final class LichessTablebaseSearch(
    backend: SttpBackend[Task, ZioStreams],
    base: Uri = uri"https://tablebase.lichess.ovh",
    probeTimeout: Duration = 1.second,
) extends Search:

  override def bestMove(
      state: GameState,
      depth: Int,
      history: Set[Long] = Set.empty,
  ): UIO[Option[Move]] =
    val fen = FenSerializer.serialize(state)
    val req = basicRequest
      .get(base.addPath("standard").addParam("fen", fen))
      .response(asStringAlways)
    backend
      .send(req)
      .map(r => LichessTablebaseSearch.parseBestMove(r.body))
      .timeout(probeTimeout)
      .map(_.flatten)
      .catchAll(_ => ZIO.none) // any failure → fall back to the normal search

object LichessTablebaseSearch:

  // The API returns far more per move (san/wdl/dtz/dtm/category/…); zio-json
  // ignores unknown fields, so we decode only what we use.
  private final case class TbMove(uci: String) derives JsonDecoder
  private final case class TbResponse(moves: List[TbMove]) derives JsonDecoder

  /** Lichess sorts `moves` best-first for the side to move, so the head is the
    * tablebase-best move to play. */
  def parseBestMove(json: String): Option[Move] =
    json.fromJson[TbResponse].toOption
      .flatMap(_.moves.headOption)
      .flatMap(m => UciCodec.parse(m.uci).toOption)

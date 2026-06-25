package chess.model

/** One half-move in an archived game. `ply` is the 0-based half-move index
  * (derived from the move's resulting FEN), which doubles as the order-free,
  * idempotent key for the archive's per-move upsert. `clockMs` (remaining clock
  * after the move) and `emtMs` (elapsed move time) are present only for clocked
  * games / sources that supply them.
  */
final case class ArchivePly(
    ply: Int,
    color: String,
    san: String,
    uci: String,
    fenAfter: String,
    occurredAt: Long,
    clockMs: Option[Long],
    emtMs: Option[Long]
)

/** A finished game, persisted for post-game analysis. The structured `plies`
  * are the source of truth; `pgn` is the same game serialized as
  * PGN-with-clocks (`%clk`/`%emt`) + `ECO`/`Opening` headers, ready to export or
  * hand to the analysis engine. `opening*` are denormalised for querying without
  * reparsing. `source` is "local" (piChess games) / "tournament" / "lichess".
  */
final case class GameArchive(
    gameId: GameId,
    source: String,
    white: String,
    black: String,
    result: String,
    timeControl: Option[String],
    initialFen: String,
    plies: List[ArchivePly],
    openingEco: Option[String],
    openingName: String,
    openingFamily: String,
    pgn: String,
    finishedAt: Long
)

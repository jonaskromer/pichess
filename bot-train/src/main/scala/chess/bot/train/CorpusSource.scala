package chess.bot.train

/** Known training-corpus sources with their quality-weight values.
  *
  * Higher quality = stronger pull on the tuner's gradient direction.
  * Picks reflect the typical signal-to-noise ratio of each source:
  *
  *   - **PgnMentor (1.0)**: curated by player and event, all master
  *     games. Almost no blunders, almost no exotic openings — the
  *     gold standard. If we had nothing else this would still be
  *     enough to train a strong eval.
  *
  *   - **Twic (0.7)**: weekly tournament archives from
  *     theweekinchess.com. High variance — some files are super-GM
  *     tournaments, others are continental opens. Still master-level
  *     overall.
  *
  *   - **Lichess (0.3)**: monthly rated-games dumps, mixed Elo. The
  *     decisive ones at Elo ≥ 2000 carry useful signal but blunders
  *     are common. Mostly useful for volume — the bot needs to see
  *     positions like "white up a rook but loses on time" without
  *     concluding that an extra rook is bad.
  *
  *   - **EngineSelfPlay (0.5)**: from our own self-play loop. No
  *     human blunders but also no human creativity; weighted in the
  *     middle so it nudges but doesn't dominate.
  *
  * Concrete values are first-pass — easy to revise once we have
  * tuner runs to compare.
  */
enum CorpusSource(val name: String, val quality: Float):
  case PgnMentor      extends CorpusSource("pgn-mentor",       1.0f)
  case Twic           extends CorpusSource("twic",             0.7f)
  case EngineSelfPlay extends CorpusSource("engine-self-play", 0.5f)
  case Lichess        extends CorpusSource("lichess",          0.3f)

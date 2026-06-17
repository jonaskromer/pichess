package chess.bot.lichess

/** Compat alias — UCI parsing/serialisation lives in the shared `codec` module
  * now (so `bot-train`'s Stockfish-tournament adapter can use it without
  * depending on `bot-lichess`). This `val` shim keeps existing `bot-lichess`
  * call sites working unchanged.
  */
val UciCodec = chess.codec.UciCodec

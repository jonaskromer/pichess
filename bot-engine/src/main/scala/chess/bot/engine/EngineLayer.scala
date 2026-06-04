package chess.bot.engine

import zio.*

/** ZLayer that builds a [[Search]] from the committed engine resources
  * (weights + opening book) — the runtime form of [[EngineBundle]].
  *
  * The standalone Lichess bot creates an [[EngineBundle]] directly in
  * its [[chess.bot.lichess.Bridge]] main; the in-process game-service
  * uses this layer because the dependency graph is already expressed
  * via ZLayer there. Both paths hit the same loaders and produce the
  * same [[Search]] — pick whichever fits the surrounding wiring.
  *
  * On a missing / malformed weights resource the layer falls back to
  * the material-only seed search via
  * [[EngineBundle.fromResourcesOrFallback]] so a bot game stays
  * playable while shipping a broken weights JSON. The failure cause
  * is logged at WARN; CI catches it via tests that pin the resources.
  */
object EngineLayer:

  /** Default layer: search built from the committed weights/v1.json +
    * openings/main-lines.pgn. */
  val live: ULayer[Search] =
    ZLayer.scoped {
      for
        result <- EngineBundle.fromResourcesOrFallback()
        (bundle, errOpt) = result
        _      <- errOpt.fold(ZIO.unit)(err =>
                    ZIO.logWarning(
                      s"EngineBundle.fromResources failed, using fallback: ${err.getMessage}",
                    )
                  )
      yield bundle.search
    }

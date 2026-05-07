package chess.controller

import zio.*

/** Per-game cache of computed annotations (legal-moves-from-square, threats,
  * attackers-of-square). Phase 1 ships only the scaffold — entries are
  * never written and `get` always misses. The point is the **invalidation
  * hook**: every successful mutation in [[WebController]] calls
  * `invalidate(gameId)`, so when Phase 3 adds the read-side endpoints they
  * just plug into the cache without having to find every mutation site
  * again.
  *
  * Cache key is `gameId` only: each game's annotations are recomputed from
  * scratch whenever its state changes (mutation → invalidate → next read
  * misses → compute + populate). For multi-state caching (e.g. analysis
  * mode that needs annotations for past positions) a `(gameId, stateHash)`
  * key would be added in Phase 3.
  */
trait AnnotationCache:
  /** Drop any cached annotations for the given game. Called after every
    * successful move / undo / redo / draw / forfeit / load.
    */
  def invalidate(gameId: String): UIO[Unit]

  /** Look up cached annotations for `gameId`. Returns `None` on miss; the
    * caller is expected to compute and `put` the result. Phase 1 always
    * misses since `put` is unreachable until Phase 3.
    */
  def get(gameId: String): UIO[Option[AnnotationCache.Annotations]]

  /** Store fresh annotations for `gameId`. Will be wired in Phase 3 when
    * the read endpoints land.
    */
  def put(gameId: String, annotations: AnnotationCache.Annotations): UIO[Unit]

object AnnotationCache:

  /** Placeholder shape for Phase 3. The actual fields land with the
    * legal-moves / threats / attackers endpoints.
    */
  final case class Annotations(
      legalMovesFrom: Map[String, List[String]],
      threats: List[String],
      attackersOf: Map[String, List[String]]
  )

  object Annotations:
    val empty: Annotations = Annotations(Map.empty, Nil, Map.empty)

  def make: UIO[AnnotationCache] =
    Ref.make(Map.empty[String, Annotations]).map(InMemoryAnnotationCache(_))

private final class InMemoryAnnotationCache(
    state: Ref[Map[String, AnnotationCache.Annotations]]
) extends AnnotationCache:
  def invalidate(gameId: String): UIO[Unit] = state.update(_ - gameId)
  def get(gameId: String): UIO[Option[AnnotationCache.Annotations]] =
    state.get.map(_.get(gameId))
  def put(gameId: String, annotations: AnnotationCache.Annotations): UIO[Unit] =
    state.update(_ + (gameId -> annotations))

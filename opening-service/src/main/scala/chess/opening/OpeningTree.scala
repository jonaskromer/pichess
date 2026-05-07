package chess.opening

import zio.*

/** Stateless interface for recording a single edge in the opening tree.
  * Implementations talk to Neo4j (or a test fake); the per-game tracker
  * that resolves "before" FENs lives in [[OpeningProjection]].
  */
trait OpeningTree:
  def recordMove(
      beforeFen: String,
      san: String,
      afterFen: String
  ): Task[Unit]

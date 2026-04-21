package chess.codec

import zio.test.*

/** Contract tests for [[FenSerializer.positionKey]] as a position-identity
  * function. The full test suite is defined in [[PositionIdentityBehaviors]]
  * and is also instantiated by [[chess.model.rules.ZobristSpec]] against
  * [[chess.model.rules.Zobrist.hash]] — both functions must satisfy the same
  * contract, and any divergence shows up when the
  * [[chess.model.rules.RepetitionEquivalenceSpec]] walks the corpus comparing
  * their partitioning.
  */
object PositionKeySensitivitySpec extends ZIOSpecDefault:
  def spec = suite("FenSerializer.positionKey")(
    PositionIdentityBehaviors.behaviors(FenSerializer.positionKey)
  )

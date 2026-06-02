package chess.repository.api

import zio.json.*

/** Wire payload for game state. Lifted out of [[RepositoryEndpoints]] so the
  * JSON codec + ADT carry their own statement coverage independent of the
  * Tapir endpoint-definition file (which lives behind an exclusion because
  * its `oneOf` codec body holds synthetic lambdas reachable only through the
  * server interpreter).
  */
final case class GameStateEnvelope(fen: String)

object GameStateEnvelope:
  given JsonEncoder[GameStateEnvelope] =
    DeriveJsonEncoder.gen[GameStateEnvelope]
  given JsonDecoder[GameStateEnvelope] =
    DeriveJsonDecoder.gen[GameStateEnvelope]

/** Typed error for [[RepositoryEndpoints.loadGame]] — distinguishes "no such
  * game" (which the client maps to `None`) from a real backend failure (which
  * the client treats as an infrastructure error).
  */
sealed trait LoadFailure

object LoadFailure:
  case object NotFound extends LoadFailure
  final case class ServerError(message: String) extends LoadFailure

/** Codec helpers pulled out as named methods so both sides of the
  * [[sttp.tapir.stringBody]] codec inside `RepositoryEndpoints.loadErrorOut`
  * are directly callable in tests. Lambda expressions inside Tapir codec
  * definitions are statement-instrumented by scoverage but can only be
  * reached end-to-end via the server interpreter — passing methods by name
  * keeps the codec readable AND unit-testable.
  */
object RepositoryCodecs:

  def serverErrorFromMessage(msg: String): LoadFailure.ServerError =
    LoadFailure.ServerError(msg)

  def serverErrorToMessage(err: LoadFailure.ServerError): String =
    err.message

package chess.bot.engine

import java.io.IOException

import scala.io.Source
import scala.util.Using

import zio.*
import zio.json.*

/** Loads [[WeightSnapshot]]s from the classpath at engine startup.
  *
  * The convention is `weights/vN.json` under any resource root on the
  * classpath. Loading from a resource (rather than a filesystem path
  * the user has to know about) means the weights are baked into the
  * deployable jar — consumers don't need to ship a separate file or
  * configure a path. Tests + dev runs read the same resource the
  * production bot does.
  *
  * Failure modes:
  *   - resource missing  → `WeightsLoader.MissingResource`
  *   - JSON malformed    → `WeightsLoader.MalformedJson`
  * Both are surfaced as ZIO failures so callers can recover (e.g.
  * fall back to a hardcoded snapshot) rather than aborting startup.
  */
object WeightsLoader:

  sealed trait LoadError extends RuntimeException
  final case class MissingResource(path: String)
      extends RuntimeException(
        s"weights resource not found: $path " +
          s"(searched classpath; ensure `botEngine/Compile/copyResources` " +
          s"ran before invoking this loader — `sbt clean compile` is a " +
          s"sure-fire reset if you've just added a new vN.json)"
      )
      with LoadError
  final case class MalformedJson(path: String, reason: String)
      extends RuntimeException(s"weights JSON malformed in $path: $reason") with LoadError

  /** Resource path convention. Override for tests with custom layouts. */
  val ResourceDir: String = "weights"

  /** Load a specific version from `weights/v$version.json`. */
  def load(version: Int): IO[LoadError, WeightSnapshot] =
    loadPath(s"$ResourceDir/v$version.json")

  /** Load any single named resource path under the classpath. */
  def loadPath(path: String): IO[LoadError, WeightSnapshot] =
    readResource(path).flatMap(parse(path, _))

  private def readResource(path: String): IO[LoadError, String] =
    ZIO
      .attemptBlocking {
        val stream = Option(getClass.getClassLoader.getResourceAsStream(path))
          .getOrElse(throw MissingResource(path))
        Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)
      }
      .refineToOrDie[LoadError]

  private def parse(path: String, body: String): IO[LoadError, WeightSnapshot] =
    ZIO.fromEither(body.fromJson[WeightSnapshot])
      .mapError(reason => MalformedJson(path, reason))

  /** Write a snapshot to a `Path` as pretty-printed JSON. The
    * companion of [[load]]: tuner runs call this to drop a new
    * version next to the existing weights resources. The path is
    * filesystem-typed (not a classpath resource) so the export step
    * can write into the build's `src/main/resources/weights/`
    * directory and be picked up by the next compile.
    */
  def writeFile(snapshot: WeightSnapshot, target: java.nio.file.Path)
      : IO[IOException, Unit] =
    ZIO.attemptBlockingIO {
      val json = snapshot.toJsonPretty
      java.nio.file.Files.createDirectories(target.getParent)
      java.nio.file.Files.writeString(target, json + "\n")
      ()
    }

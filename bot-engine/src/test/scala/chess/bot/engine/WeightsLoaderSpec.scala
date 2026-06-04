package chess.bot.engine

import java.nio.file.Files

import zio.*
import zio.test.*

object WeightsLoaderSpec extends ZIOSpecDefault:

  def spec = suite("WeightsLoader")(
    suite("load (classpath resource)")(
      test("reads the committed v1 default") {
        for snap <- WeightsLoader.load(version = 1)
        yield assertTrue(
          snap.version == 1,
          snap.weights("pawn") == 100,
          snap.weights("knight") == 320,
          snap.weights("queen") == 900,
        )
      },
      test("fails with MissingResource for a non-existent version") {
        for result <- WeightsLoader.load(version = 999).exit
        yield assertTrue(
          result.causeOption.exists(_.failureOption.exists {
            case _: WeightsLoader.MissingResource => true
            case _                                => false
          })
        )
      },
    ),
    suite("loadPath")(
      test("fails with MissingResource when the path doesn't exist") {
        for result <- WeightsLoader.loadPath("weights/nope.json").exit
        yield assertTrue(
          result.causeOption.exists(_.failureOption.exists {
            case _: WeightsLoader.MissingResource => true
            case _                                => false
          })
        )
      },
    ),
    suite("writeFile + readResource round-trip")(
      test("writeFile produces a file the JSON parser can read back") {
        for
          tmp <- ZIO.attempt(Files.createTempFile("pichess-weights-", ".json"))
                   .ensuring(ZIO.unit)
          snap = WeightSnapshot(
                   version = 42,
                   weights = Map("pawn" -> 105, "knight" -> 325),
                 )
          _      <- WeightsLoader.writeFile(snap, tmp)
          bytes  <- ZIO.attempt(Files.readString(tmp))
          parsed <- ZIO.fromEither(
                      zio.json.JsonDecoder[WeightSnapshot].decodeJson(bytes)
                    )
          _      <- ZIO.attempt(Files.delete(tmp))
        yield assertTrue(parsed == snap)
      },
    ),
  )

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "pichess",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"       % "2.1.14",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.scalafx"   %% "scalafx"   % "22.0.0-R33"
    ) ++ {
      lazy val osName = System.getProperty("os.name") match {
        case n if n.startsWith("Linux")   => "linux"
        case n if n.startsWith("Mac")     => System.getProperty("os.arch") match {
          case "x86_64" | "amd64" => "mac"
          case "aarch64"          => "mac-aarch64"
          case _                  => "mac"
        }
        case n if n.startsWith("Windows") => "win"
        case _                            => throw new Exception("Unknown platform!")
      }
      Seq("base", "controls", "graphics")
        .map(m => "org.openjfx" % s"javafx-$m" % "22.0.1" classifier osName)
    },
    // Main is excluded: it is a pure ZIO wiring boundary with no testable logic
    coverageExcludedFiles := ".*Main.*",
    coverageEnabled := true,
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum := true,
  )

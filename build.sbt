ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "pichess",
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio"                       % "2.1.24",
      "dev.zio"                %% "zio-http"                  % "3.10.1",
      "org.scala-lang.modules" %% "scala-parser-combinators"  % "2.4.0",
      "com.lihaoyi"            %% "fastparse"                 % "3.1.1",
      "dev.zio"                %% "zio-test"                  % "2.1.24" % Test,
      "dev.zio"                %% "zio-test-sbt"              % "2.1.24" % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Main is excluded: ZIO wiring with console I/O and server lifecycle
    // WebController route definitions/handlers are tested via WebControllerRoutesSpec
    // but scoverage doesn't instrument zio-http structural wiring (given, Routes, handler)
    coverageExcludedFiles := ".*Main.*|.*WebController.*",
    coverageEnabled := true,
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum := true,
  )

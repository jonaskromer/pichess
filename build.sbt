ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "pichess",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"       % "2.1.14",
      "dev.zio"       %% "zio-http"  % "3.10.1",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    ),
    // Main and WebController are excluded: ZIO/HTTP wiring boundaries with no pure testable logic
    coverageExcludedFiles := ".*Main.*|.*WebController.*",
    coverageEnabled := true,
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum := true,
  )

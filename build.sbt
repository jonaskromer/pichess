ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "pichess",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"       % "2.1.14",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    ),
    // Main is excluded: it is a pure ZIO wiring boundary with no testable logic
    coverageExcludedFiles := ".*Main.*",
    coverageEnabled := true,
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum := true,
  )

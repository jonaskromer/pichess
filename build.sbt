ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "pichess",
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio"                       % "2.1.24",
      "dev.zio"                %% "zio-http"                  % "3.10.1",
      "dev.zio"                %% "zio-process"               % "0.7.2",
      "dev.zio"                %% "zio-json"                  % "0.9.0",
      "org.scala-lang.modules" %% "scala-parser-combinators"  % "2.4.0",
      "com.lihaoyi"            %% "fastparse"                 % "3.1.1",
      "dev.zio"                %% "zio-test"                  % "2.1.24" % Test,
      "dev.zio"                %% "zio-test-sbt"              % "2.1.24" % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // JsonCodec is a thin one-liner shim around zio-json's derived encoders;
    // scoverage cannot see through the macro-derived givens.
    coverageExcludedFiles := ".*JsonCodec.*",
    coverageEnabled := true,
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum := true,
  )

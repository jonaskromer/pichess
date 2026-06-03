addSbtPlugin("org.scoverage"      % "sbt-scoverage"             % "2.2.1")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"              % "2.5.2")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalafix"              % "0.13.0")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"               % "1.21.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"  % "1.3.2")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"       % "1.10.4")
addSbtPlugin("com.thesamet"       % "sbt-protoc"                % "1.0.7")
addSbtPlugin("io.gatling"         % "gatling-sbt"               % "4.8.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"                   % "0.4.7")

libraryDependencies +=
  "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.3"

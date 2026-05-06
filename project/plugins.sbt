addSbtPlugin("org.scoverage"      % "sbt-scoverage"             % "2.2.1")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"              % "2.5.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"               % "1.20.1")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"  % "1.3.2")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"       % "1.10.4")
addSbtPlugin("com.thesamet"       % "sbt-protoc"                % "1.0.7")

libraryDependencies +=
  "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.3"

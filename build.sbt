name := "tcp-test"
version := "0.0.1"

scalaVersion := "2.13.1"

resolvers += "Sonatype Public" at "https://oss.sonatype.org/content/groups/public/"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ywarn-unused:_,imports",
  "-Ywarn-unused:imports"
)

val CatsVersion   = "2.1.1"
val CirceVersion  = "0.12.3"
val TsecVersion   = "0.2.0"
val http4sVersion = "0.21.4"

libraryDependencies ++= Seq(
  "org.typelevel"         %% "cats-core"     % CatsVersion,
  "org.typelevel"         %% "cats-effect"   % CatsVersion,
  "io.circe"              %% "circe-core"    % CirceVersion,
  "io.circe"              %% "circe-parser"  % CirceVersion,
  "io.circe"              %% "circe-generic" % CirceVersion,
  "co.fs2"                %% "fs2-io"        % "2.3.0",
  "com.comcast"           %% "ip4s-cats"     % "1.3.0",
  "org.scodec"            %% "scodec-stream" % "2.0.0",
  "org.jline"             % "jline"          % "3.12.1",
  "com.github.pureconfig" %% "pureconfig"    % "0.12.2",
  "io.github.jmcardon"    %% "tsec-common"   % TsecVersion,
  //  "io.github.jmcardon" %% "tsec-password" % TsecVersion,
  "io.github.jmcardon" %% "tsec-cipher-jca"    % TsecVersion,
  "io.github.jmcardon" %% "tsec-cipher-bouncy" % TsecVersion,
  "io.github.jmcardon" %% "tsec-mac"           % TsecVersion,
  "io.github.jmcardon" %% "tsec-signatures"    % TsecVersion,
  "io.github.jmcardon" %% "tsec-hash-jca"      % TsecVersion,
  "io.github.jmcardon" %% "tsec-hash-bouncy"   % TsecVersion,
  // "io.github.jmcardon" %% "tsec-libsodium"     % TsecVersion,
  //  "io.github.jmcardon" %% "tsec-jwt-mac" % TsecVersion,
  //  "io.github.jmcardon" %% "tsec-jwt-sig" % TsecVersion,
  //  "io.github.jmcardon" %% "tsec-http4s" % TsecVersion
  "org.http4s" %% "http4s-dsl"          % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-circe"        % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion
)

addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.11.0" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")

addCommandAlias("fix", "all compile:scalafix test:scalafix")

scalafixDependencies in ThisBuild += "com.nequissimus" %% "sort-imports" % "0.3.2"

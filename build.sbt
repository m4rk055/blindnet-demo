name := "blindnet-demo"
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

assemblyMergeStrategy in assembly := {
  case x if Assembly.isConfigFile(x) =>
    MergeStrategy.concat
  case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
    MergeStrategy.rename
  case PathList("META-INF", xs @ _*) =>
    (xs map { _.toLowerCase }) match {
      case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) =>
        MergeStrategy.discard
      case ps @ (x :: xs) if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
        MergeStrategy.discard
      case "services" :: xs =>
        MergeStrategy.filterDistinctLines
      case ("io.netty.versions.properties" :: Nil) =>
        MergeStrategy.concat
      case _ => MergeStrategy.deduplicate
    }
  case "module-info.class" => MergeStrategy.filterDistinctLines
  case _                   => MergeStrategy.deduplicate
}

// mainClass in assembly := Some("blindnet.app.MainAlice")
// assemblyJarName in assembly := "MainAlice.jar"

// mainClass in assembly := Some("blindnet.app.MainBob")
// assemblyJarName in assembly := "MainBob.jar"

// mainClass in assembly := Some("blindnet.monitor.Monitor")
// assemblyJarName in assembly := "Monitor.jar"

// mainClass in assembly := Some("blindnet.msgpool.MsgPool")
// assemblyJarName in assembly := "MsgPool.jar"

// mainClass in assembly := Some("blindnet.router.RouterApp1")
// assemblyJarName in assembly := "RouterApp1.jar"

// mainClass in assembly := Some("blindnet.router.RouterApp2")
// assemblyJarName in assembly := "RouterApp2.jar"

// mainClass in assembly := Some("blindnet.router.RouterApp3")
// assemblyJarName in assembly := "RouterApp3.jar"

// mainClass in assembly := Some("blindnet.router.RouterApp4")
// assemblyJarName in assembly := "RouterApp4.jar"

// mainClass in assembly := Some("blindnet.router.RouterApp5")
// assemblyJarName in assembly := "RouterApp5.jar"

mainClass in assembly := Some("blindnet.router.RouterApp6")
assemblyJarName in assembly := "RouterApp6.jar"

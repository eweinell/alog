name := "alog"

scalaVersion := "2.11.1"

resolvers ++= Seq(
  "Java.net Maven2 Repository"		at "http://download.java.net/maven/2/",
  "Sonatype scala-tools repo"		at "https://oss.sonatype.org/content/groups/scala-tools/",
  "Sonatype scala-tools releases"	at "https://oss.sonatype.org/content/repositories/releases",
  "Sonatype scala-tools snapshots"	at "https://oss.sonatype.org/content/repositories/snapshots",
  "spray.io"						at "http://repo.spray.io",
  "Neo4J releases"					at "http://m2.neo4j.org/releases",
  "Typesafe Repository"				at "http://repo.typesafe.com/typesafe/releases/",
  "chrisdinn.github.io"				at "https://bintray.com/etaty/maven/rediscala/view/general"
)

  
  libraryDependencies ++= {
	val liftVersion = "3.0-M1"
	val akkaVersion = "2.3.3"
	val sprayVersion = "1.3.1"
	Seq(
//		"net.liftweb"				%% "lift-webkit"		% liftVersion	% "compile",
//		"net.liftweb"				%% "lift-json"			% liftVersion	% "compile",
		// "net.liftweb"			%% "lift-mapper"		% liftVersion	% "compile",
		"ch.qos.logback"			%  "logback-classic"	% "1.0.6",
		"junit"						%  "junit"				% "4.11"			% "test",
		"org.specs2"				%% "specs2"				% "2.3.13"		% "test",
		"org.pegdown"				%  "pegdown"			% "1.1.0"		% "test",
//		"org.scalatest"				%% "scalatest"			% "2.0.M5b"		% "test",
		"org.eclipse.jetty"			%  "jetty-webapp"		% "8.1.7.v20120910"		%  "container,compile",
		"org.eclipse.jetty.orbit"	%  "javax.servlet"		% "3.0.0.v201112011016"	%  "container,compile" artifacts Artifact("javax.servlet", "jar", "jar"),
//		"net.liftmodules"			%% "mapperauth_2.6"		% "0.2-SNAPSHOT"		%  "compile" intransitive(),
//		"net.liftmodules"			%% "extras_2.5"			% "0.3"			%  "compile" intransitive(),
//		"com.h2database"			%  "h2"					% "1.2.138",
		"net.databinder.dispatch" 	%% "dispatch-core"		% "0.11.1", 
		"com.typesafe.akka"			%% "akka-actor"			% akkaVersion,
		"com.typesafe.akka"			%% "akka-testkit"		% akkaVersion	% "test",
//		"com.typesafe.akka"			%% "akka-quartz-scheduler" % "1.2.0-akka-2.2.x",
		"com.typesafe.trace"		%% "trace-akka-2.3.3"	% "0.1.3",			
		"com.etaty.rediscala"		%% "rediscala"			% "1.3.1",
		"org.mongodb"				%% "casbah"				% "2.7.3",
		"org.scala-lang"			%% "scala-pickling"		% "0.8.0",
		"io.spray"					%% "spray-can"			% sprayVersion,
		"io.spray"					%% "spray-routing"		% sprayVersion,
		"io.spray"					%% "spray-httpx"		% sprayVersion,
		"io.spray"					%% "spray-json"			% "1.2.6",
		"io.spray"					%% "spray-testkit"		% sprayVersion	% "test",
		"joda-time"					% "joda-time"			% "2.3",
		"org.joda"					% "joda-convert"		% "1.2"
//		"eu.fakod"					%% "neo4j-scala"		% "0.3.0",
//		"org.anormcypher"			%% "anormcypher"		% "0.4.4",
//		"org.neo4j"					% "neo4j"				% "2.0.3"
  )
}

buildInfoSettings

sourceGenerators in Compile <+= buildInfo

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "code"

seq(lessSettings:_*)

(LessKeys.filter in (Compile, LessKeys.less)) := "styles.less"

(LessKeys.mini in (Compile, LessKeys.less)) := true

(sourceDirectory in (Compile, LessKeys.less)) <<= (sourceDirectory in Compile)(_ / "webapp" / "media" / "styles")

seq(closureSettings:_*)

(ClosureKeys.prettyPrint in (Compile, ClosureKeys.closure)) := false

seq(webSettings :_*)

// add managed resources, where less and closure publish to, to the webapp
(webappResources in Compile) <+= (resourceManaged in Compile)

(resourceManaged in (Compile, LessKeys.less)) <<= (crossTarget in Compile)(_ / "resource_managed" / "main" / "media" / "styles")

(sourceDirectory in (Compile, ClosureKeys.closure)) <<= (sourceDirectory in Compile)(_ / "webapp" / "media" / "js")

(resourceManaged in (Compile, ClosureKeys.closure)) <<= (crossTarget in Compile)(_ / "resource_managed" / "main" / "media" / "js")

// If using JRebel uncomment next line
scanDirectories := Nil

// Remove Java directories, otherwise sbteclipse generates them
unmanagedSourceDirectories in Compile <<= (scalaSource in Compile)(Seq(_))

unmanagedSourceDirectories in Test <<= (scalaSource in Test)(Seq(_))

EclipseKeys.withSource := true

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

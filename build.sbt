name := "lib"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.14",
  "org.scalaz" %% "scalaz-core" % "7.2.7",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.14" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)

scalacOptions ++= Seq( "-unchecked", "-deprecation", "-feature" )

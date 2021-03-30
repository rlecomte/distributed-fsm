import Dependencies._

ThisBuild / scalaVersion := "2.13.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"

val ScalacOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-feature",
  "-Xfatal-warnings",
  "-Ywarn-unused"
)
scalacOptions := ScalacOptions

lazy val root = (project in file("."))
  .settings(
    name := "distributed-fsm",
    libraryDependencies += "org.typelevel" %% "cats-effect" % "2.3.1",
    libraryDependencies += "org.typelevel" %% "cats-free" % "2.3.1",
    libraryDependencies += scalaTest % Test
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.

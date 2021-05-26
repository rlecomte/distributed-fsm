import Dependencies._

val circeVersion = "0.12.3"

ThisBuild / scalaVersion := "2.13.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "io.rlecomte"
ThisBuild / organizationName := "distributed-fsm"

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

lazy val root = (project in file("."))
  .aggregate(core, examples)

lazy val core = (project in file("core"))
  .settings(
    name := "core",
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.1.0",
    libraryDependencies += "org.typelevel" %% "cats-free" % "2.3.1",
    libraryDependencies += scalacheckEffect % Test,
    libraryDependencies += scalacheckEffectMunit % Test,
    libraryDependencies += catsEffectMunit % Test,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.3" cross CrossVersion.full),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    testFrameworks += new TestFramework("munit.Framework"),
    scalacOptions := ScalacOptions
  )

lazy val examples = (project in file("examples"))
  .dependsOn(core)
  .settings()

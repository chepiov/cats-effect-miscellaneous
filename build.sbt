name := "cats-effect"

version := "1.0"

scalaVersion := "2.12.8"

libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0" withSources() withJavadoc()

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0")

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
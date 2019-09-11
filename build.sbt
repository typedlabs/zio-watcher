inThisBuild(
  List(
    organization := "com.typedlabs",
    homepage := Some(url("https://github.com/typedlabs/zio-watcher/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("imjsilva", "Joao Da Silva", "joao@typedlabs.com", url("https://typedlabs.com"))
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

// cancelable in Global := true

lazy val zioWatcher = (project in file("."))
    .settings(
      name := "zio-watcher",
      version := "0.0.1",
      scalaVersion := "2.12.8",
      scalacOptions -= "-Yno-imports",
      scalacOptions -= "-Xfatal-warnings",
      crossScalaVersions := Seq(scalaVersion.value, "2.11.8"),
      bintrayRepository := "releases",
      bintrayOrganization := Some("typedlabs"),
      bintrayPackageLabels := Seq("scala", "zio", "watcher", "filesystem"),
      bintrayVcsUrl := Some("https://github.com/typedlabs/zio-watcher"),
      publishMavenStyle := false,
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio" % "1.0.0-RC12-1",
        "dev.zio" %% "zio-streams" % "1.0.0-RC12-1",
        "ch.qos.logback" %  "logback-classic" % "1.1.6",      
        "io.laserdisc" %% "log-effect-zio" % "0.9.0"
      )
    )

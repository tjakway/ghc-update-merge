name := "update-merge"
version := "1.0"
scalaVersion := "2.12.2"

resolvers += Resolver.typesafeIvyRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")


//set the main class
//see https://stackoverflow.com/questions/6467423/how-to-set-main-class-in-build
mainClass in Compile := Some("com.jakway.tools.ghc.UpdateMerge")

//ignore anything named snippets.scala
excludeFilter in unmanagedSources := HiddenFileFilter || "snippets.scala"

//enable more warnings
scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-feature")

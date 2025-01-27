name := "PredictionApp"

version := "1.0"

scalaVersion := "2.12.15"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql" % "3.3.0" % Provided,
  "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.1",
  "ml.dmlc" %% "xgboost4j-spark" % "2.1.3",
  "org.apache.spark" %% "spark-mllib" % "3.3.0" % Provided
)

assembly / assemblyMergeStrategy := {
  case "META-INF/MANIFEST.MF" => MergeStrategy.discard
  case "META-INF/*.SF"        => MergeStrategy.discard
  case "META-INF/*.DSA"       => MergeStrategy.discard
  case "META-INF/*.RSA"       => MergeStrategy.discard
  case _                      => MergeStrategy.first
}
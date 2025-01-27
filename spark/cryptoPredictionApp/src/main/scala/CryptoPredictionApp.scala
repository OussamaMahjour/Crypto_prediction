import org.apache.spark.sql.{DataFrame, SparkSession}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.util.UUID
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming._
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.regression.{GeneralizedLinearRegression, DecisionTreeRegressor}
import org.apache.spark.ml.evaluation.RegressionEvaluator
import ml.dmlc.xgboost4j.scala.spark.{XGBoostRegressor, XGBoostClassifier}
import org.apache.spark.ml.feature.StringIndexer
import ml.dmlc.xgboost4j.scala.spark.XGBoostRegressionModel



object CryptoPredictionApp {
  def main(args: Array[String]): Unit = {
    val spark = initializeSparkSession("CryptoPrediction")
    configureSpark(spark)



    val kafkaBootstrapServers = "kafka-data:9092"
    val kafkaTopic = "prediction"
    val hdfsPath = "hdfs://namenode:9000/bitcoin/dataset.csv"

    val rawData = loadData(spark, hdfsPath)
    val processedData = processRawData(rawData)
    processedData.show(4)

    val xgboostModel = trainXGBoostModel(processedData)

    val futureData = generateFuturePredictions(spark, processedData, xgboostModel, steps = 4, interval = 60)
    futureData.show(10)

  }


  def initializeSparkSession(appName: String): SparkSession = {
    SparkSession.builder()
      .appName(appName)
      .getOrCreate()
  }

  def configureSpark(spark: SparkSession): Unit = {
    spark.conf.set("spark.sql.shuffle.partitions", 4)

  }

  def loadData(spark: SparkSession, path: String): DataFrame = {
    spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .option("timestampFormat", "yyyy-MM-dd HH:mm:ss.SSS")
      .csv(path)
  }

  def processRawData(rawData: DataFrame): DataFrame = {
    val windowSpec = Window.orderBy("time")
    rawData
      .withColumn("time", unix_timestamp(to_timestamp(col("open_time"))))
      .withColumn("price", (col("high_price") + col("low_price")) / 2)
      .withColumn("avg_of_last_100_price", avg("price").over(windowSpec.rowsBetween(-99, 0)))
      .withColumn("avg_of_last_1000_price", avg("price").over(windowSpec.rowsBetween(-999, 0)))
      .withColumn("avg_of_last_10000_price", avg("price").over(windowSpec.rowsBetween(-9999, 0)))
      .select("time", "price", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price")
      .repartition(4, col("time"))
  }

  def trainXGBoostModel(processedData: DataFrame): XGBoostRegressionModel = {
    val vectorAssembler = new VectorAssembler()
      .setInputCols(Array("time", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price"))
      .setOutputCol("features")

    val xgbInput = vectorAssembler.transform(processedData).select("features", "price")

    val xgboostParams = Map(
      "eta" -> 0.1,
      "max_depth" -> 6,
      "objective" -> "reg:squarederror",
      "num_round" -> 100,
      "num_workers" -> 1,
      "subsample" -> 0.8,
      "colsample_bytree" -> 0.8
    )

    new XGBoostRegressor(xgboostParams)
      .setFeaturesCol("features")
      .setLabelCol("price")
      .fit(xgbInput)
  }

  def generateFuturePredictions(
                                 spark: SparkSession,
                                 processedData: DataFrame,
                                 model: XGBoostRegressionModel,
                                 steps: Int,
                                 interval: Int
                               ): DataFrame = {
    import spark.implicits._

    val lastRow = processedData.orderBy(desc("time")).limit(1).cache()
    var time = lastRow.select("time").as[Long].head()
    var futureData = processedData

    for (step <- 1 to steps) {
      val last100PriceAvg = futureData.orderBy(desc("time")).limit(100).agg(avg("price")).as[Double].head()
      val last1000PriceAvg = futureData.orderBy(desc("time")).limit(1000).agg(avg("price")).as[Double].head()
      val last10000PriceAvg = futureData.orderBy(desc("time")).limit(10000).agg(avg("price")).as[Double].head()

      val newRow = Seq((time + step * interval, last100PriceAvg, last1000PriceAvg, last10000PriceAvg))
      val newFeatureData = spark.createDataFrame(newRow).toDF("time", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price")

      val vectorAssembler = new VectorAssembler()
        .setInputCols(Array("time", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price"))
        .setOutputCol("features")

      val assembledData = vectorAssembler.transform(newFeatureData)
      val predictedPrice = model.transform(assembledData).select("prediction").as[Double].head()

      val result = newFeatureData.withColumn("price", lit(predictedPrice))
      futureData = futureData.union(result)

      time += interval
    }

    futureData
  }
}
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
object App {

  def main(args: Array[String]): Unit = {
    val spark = initializeSparkSession("CryptoPrediction")
    spark.conf.set("spark.sql.shuffle.partitions", 4)   //spark.sparkContext.setLogLevel("ERROR")
    val kafkaBootstrapServers = "kafka-data:9092"
    val kafkaTopic = "prediction"
  //  spark.sparkContext.setLogLevel("DEBUG")

    val hdfsPath = "hdfs://namenode:9000/bitcoin/dataset.csv"
    val rawData = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .option("timestampFormat", "yyyy-MM-dd HH:mm:ss.SSS")
      .csv(hdfsPath)

    rawData.show(5)

    // Calculate the required columns
    val processedData = rawData
      .withColumn("time", unix_timestamp(to_timestamp(col("open_time"))) )
      .withColumn("price", (col("high_price") + col("low_price")) / 2)
      .withColumn("avg_of_last_100_price", avg("price").over(Window.partitionBy("time").orderBy("time").rowsBetween(-99, 0)))
      .withColumn("avg_of_last_1000_price", avg("price").over(Window.partitionBy("time").orderBy("time").rowsBetween(-999, 0)))
      .withColumn("avg_of_last_10000_price", avg("price").over(Window.partitionBy("time").orderBy("time").rowsBetween(-9999, 0)))
      .select("time", "price", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price")

    processedData.show(4)





    val vectorAssembler = new VectorAssembler().
      setInputCols(Array("time", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price")).
      setOutputCol("features")

    val xgbInput = vectorAssembler.transform(processedData).select("features", "price")


    // XGBoost configuration
    val xgboostParams = Map(
      "eta" -> 0.1,
      "max_depth" ->2,
      "objective" -> "reg:squarederror",
      "num_round" -> 50,
      "num_workers" -> 1,
      "use_external_memory" -> true
    )

    val xgboost = new XGBoostRegressor(xgboostParams).
      setNumRound(100).
      setNumWorkers(1)
      .setFeaturesCol("features")
      .setLabelCol("price")

    println("fiting")
    // Train the XGBoost model
    val xgboostModel = xgboost.fit(xgbInput.repartition(4,col("time")))

    // Make predictions
//    val predictions = xgboostModel.transform(testFeatures)
//
//    // Evaluate RMSE
//    val evaluator = new RegressionEvaluator()
//      .setLabelCol("price")
//      .setPredictionCol("prediction")
//      .setMetricName("rmse")
//
//    val rmse = evaluator.evaluate(predictions)
//    println(s"Root Mean Squared Error (RMSE): $rmse")
    // Convert the processed data to JSON format for Kafka
//

    println("Data pushed to Kafka successfully!")
    import spark.implicits._

    val lastRow = processedData.orderBy(desc("time")).limit(1)

    var futureData = processedData
    var time = lastRow.select("time").as[Long].head() // Get the last time value

    // Define number of future steps and interval (e.g., 100 steps, interval 60)
    val futureSteps = 20
    val interval = 60

    // Generate future predictions
    for (step <- 1 to futureSteps) {
      // Calculate the moving averages for the last 100, 1000, and 10000 prices (based on historical data)
      val last100PriceAvg = futureData.orderBy(desc("time")).limit(100).agg(avg("price")).first().getDouble(0)
      val last1000PriceAvg = futureData.orderBy(desc("time")).limit(1000).agg(avg("price")).first().getDouble(0)
      val last10000PriceAvg = futureData.orderBy(desc("time")).limit(10000).agg(avg("price")).first().getDouble(0)

      // Create a new row with future features (make sure it has the same structure as processedData)
      val newRow = Seq(
        (time + step * interval, last100PriceAvg, last1000PriceAvg, last10000PriceAvg) // Simulate future time and avg prices
      )

      // Convert new row to DataFrame with the same schema as processedData
      val newFeatureData = spark.createDataFrame(newRow).toDF("time", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price")

      // Assemble features using VectorAssembler
      val vectorAssembler = new VectorAssembler()
        .setInputCols(Array("time", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price"))
        .setOutputCol("features")

      val assembledData = vectorAssembler.transform(newFeatureData)

      // Predict the price using the XGBoost model
      val predictedPrice = xgboostModel.transform(assembledData).select("prediction").head().getDouble(0)

      // Add predicted price to the new row (ensure the new structure matches processedData)
      val result = newFeatureData.withColumn("price", lit(predictedPrice))

      // Append the new row to futureData
      futureData = futureData.union(result)

      val kafkaData = result
        .withColumn("value", to_json(struct(
          col("time"),
          col("prediction"),
        )))
        .selectExpr("CAST(null AS STRING) AS key", "value")
      kafkaData.write
        .format("kafka")
        .option("kafka.bootstrap.servers", kafkaBootstrapServers)
        .option("topic", kafkaTopic)
        .save()

      // Update time for next iteration
      time += interval
    }

    // Show the results
    futureData.show(10)



  }

  /**
   * Initialize the Spark session
   * @param appName Application name
   * @return SparkSession instance
   */
  def initializeSparkSession(appName: String): SparkSession = {
    SparkSession.builder()
      .appName(appName)
      .config("spark.sql.windowExec.buffer.spill.threshold", "4194304")// Adjust for your cluster
      .getOrCreate()
  }

  /**
   * Create a DataFrame with messages to write to Kafka
   * @param spark SparkSession instance
   * @return DataFrame containing messages (key, value)
   */
  def createMessageDataFrame(spark: SparkSession): DataFrame = {
    import spark.implicits._
    spark.createDataFrame(Seq(
      ("key1", "This is my first message!"),
      ("key2", "This is my second message!")
    )).toDF("key", "value")
  }

  /**
   * Write messages to a Kafka topic
   * @param df DataFrame containing messages
   * @param kafkaBootstrapServers Kafka bootstrap server addresses
   * @param kafkaTopic Kafka topic name
   */
  def writeToKafka(df: DataFrame, kafkaBootstrapServers: String, kafkaTopic: String): Unit = {
    df.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
      .write
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("topic", kafkaTopic)
      .save()
    println(s"Messages written to Kafka topic: $kafkaTopic")
  }

  /**
   * Read messages from a Kafka topic as a streaming DataFrame
   * @param spark SparkSession instance
   * @param kafkaBootstrapServers Kafka bootstrap server addresses
   * @param kafkaTopic Kafka topic name
   * @return DataFrame representing the Kafka stream
   */
  def readFromKafka(spark: SparkSession, kafkaBootstrapServers: String, kafkaTopic: String): DataFrame = {
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("subscribe", kafkaTopic)
      .option("startingOffsets", "earliest") // Start reading from the earliest offsets
      .load()
  }

  /**
   * Process the Kafka stream and output to console
   * @param kafkaStream DataFrame representing the Kafka stream
   */
  def processKafkaStream(kafkaStream: DataFrame): Unit = {
    kafkaStream
      .selectExpr("CAST(value AS STRING)")
      .writeStream
      .format("console")
      .outputMode("append")
      .start()
      .awaitTermination()
  }
}
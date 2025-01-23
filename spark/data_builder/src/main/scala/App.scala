import org.apache.spark.sql.{DataFrame, SparkSession}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.util.UUID
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming._

object App {

  def main(args: Array[String]): Unit = {
    val spark = initializeSparkSession("CryptoPrediction")
    spark.sparkContext.setLogLevel("ERROR")
    val kafkaBootstrapServers = "kafka-data:9092"
    val kafkaTopic = "prediction"

    val hdfsPath = "hdfs://namenode:9000/bitcoin/dataset.csv"
    val rawData = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(hdfsPath)

    rawData.show()

    // Calculate the required columns
    val processedData = rawData
      .withColumn("time", (col("close_time") - col("open_time")) / 2)
      .withColumn("price", (col("high_price") + col("low_price")) / 2)
      .withColumn("avg_of_last_100_price", avg("price").over(Window.orderBy("time").rowsBetween(-99, 0)))
      .withColumn("avg_of_last_1000_price", avg("price").over(Window.orderBy("time").rowsBetween(-999, 0)))
      .withColumn("avg_of_last_10000_price", avg("price").over(Window.orderBy("time").rowsBetween(-9999, 0)))
      .select("time", "price", "avg_of_last_100_price", "avg_of_last_1000_price", "avg_of_last_10000_price")

    // Convert the processed data to JSON format for Kafka
    val kafkaData = processedData
      .withColumn("value", to_json(struct(
        col("time"),
        col("price"),
        col("avg_of_last_100_price"),
        col("avg_of_last_1000_price"),
        col("avg_of_last_10000_price")
      )))
      .selectExpr("CAST(null AS STRING) AS key", "value")
    kafkaData.write
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("topic", kafkaTopic)
      .save()

    println("Data pushed to Kafka successfully!")


  }

  /**
   * Initialize the Spark session
   * @param appName Application name
   * @return SparkSession instance
   */
  def initializeSparkSession(appName: String): SparkSession = {
    SparkSession.builder()
      .appName(appName) // Adjust for your cluster
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
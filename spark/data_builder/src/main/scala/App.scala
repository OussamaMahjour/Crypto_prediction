import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window


object App {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder.appName("Simple Application").getOrCreate()
    import spark.implicits._
    val dataset = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .option("timestampFormat", "yyyy-MM-dd HH:mm:ss.SSS")
      .csv("hdfs://namenode:9000/dataset/dataset2.csv")


    val data = dataset.withColumn("close_time", to_timestamp($"close_time", "yyyy-MM-dd HH:mm:ss.SSS"))
    val windowSpec = Window.orderBy("close_time").rowsBetween(-10, 0) // 10-minute moving average
    data.printSchema()
    // Calculate moving averages for each feature in parallel
    val movingAvgData = data.withColumn("close_price_ma", avg($"close_price").over(windowSpec)).select("close_time","close_price_ma")

    val result = movingAvgData.withColumn("close_time", date_format($"close_time", "yyyy-MM-dd HH:mm:ss.SSS"))
    val rdd = result.rdd.map(row => row.mkString(","))
    // Save each moving average DataFrame to a separate dataset
    rdd.saveAsTextFile("hdfs://namenode:9000/dataset/moving_avg.csv")

    val ma = spark.read.option("header", "true")
      .csv("hdfs://namenode:9000/dataset/close_price_moving_avg.csv")

    ma.show()
    spark.stop()
  }
}
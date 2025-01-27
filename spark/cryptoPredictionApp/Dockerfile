FROM apache/spark:3.5.4-scala2.12-java17-python3-r-ubuntu

# Set the working directory

WORKDIR /app
# Copy your Spark application JAR file into the container
COPY ./target/scala-2.12/PredictionApp-assembly-1.0.jar /app/your-app.jar

RUN ls /app
# Command to run the Spark application
CMD ["/opt/spark/bin/spark-submit", \
   #  "--packages","org.apache.spark:spark-sql-kafka-0-10_2.12:3.3.0,ml.dmlc:xgboost4j-spark_2.12:2.1.3", \
     "--class", "App", \
     "--executor-memory","3g","--driver-memory","3g",\
     "--executor-cores","4","--driver-cores","4",\
     "--master","spark://spark-master:7077",\
     "--deploy-mode", "client", \
     "/app/your-app.jar"]

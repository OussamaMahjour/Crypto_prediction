FROM bde2020/spark-master:latest

# Set the working directory
WORKDIR /app

# Copy your Spark application JAR file into the container
COPY ./target/scala-2.12/simple-project_2.12-1.0.jar /app/your-app.jar

# Command to run the Spark application
CMD ["/spark/bin/spark-submit", \
     "--class", "App", \
     "--master", "spark://spark-master:7077", \
     "--deploy-mode", "client", \
     "/app/your-app.jar"]

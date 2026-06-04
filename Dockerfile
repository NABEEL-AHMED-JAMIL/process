# Use a lightweight OpenJDK 17 image
FROM eclipse-temurin:17-jdk

# Maintainer info
LABEL maintainer="nabeel.amd93@gmail.com"

# Set working directory
WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Expose port for application
EXPOSE 9098

# Add a volume for temp files
VOLUME /tmp
VOLUME /app/logs

# Argument for the JAR file
ARG JAR_FILE=target/process-1.0-0.jar

# Copy the JAR into the container
COPY ${JAR_FILE} app.jar

# Create logs directory
RUN mkdir -p /app/logs

# Run the JAR
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

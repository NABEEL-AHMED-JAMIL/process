# Use a lightweight OpenJDK image
FROM openjdk:8-jdk-alpine

# Maintainer info
LABEL maintainer="nabeel.amd93@gmail.com"

# Expose port
EXPOSE 9098

# Add a volume for temp files (optional)
VOLUME /tmp

# Argument for the JAR file
ARG JAR_FILE=target/process-1.0-0.jar

# Copy the JAR into the container
COPY ${JAR_FILE} app.jar

# Run the JAR
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app.jar"]

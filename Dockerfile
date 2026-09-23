# Use a lightweight OpenJDK 17 image
FROM eclipse-temurin:17-jdk

# Maintainer info
LABEL maintainer="nabeel.amd93@gmail.com"

# Set working directory
WORKDIR /app

# Install curl for health checks and ffmpeg for audio upload transcoding (ALAC -> AAC), which
# Storage's upload path still does. LibreOffice left with Media & Documents (MIG-48): media-service
# carries it, sized for it, and process no longer converts anything.
#
# Fonts stay: eclipse-temurin ships with essentially none, and anything rendering text in this JVM
# would otherwise fall back silently. fonts-liberation covers Arial/Times/Courier metrics,
# carlito/caladea cover Calibri/Cambria, dejavu is the general fallback.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ffmpeg \
        fonts-liberation fonts-crosextra-carlito fonts-crosextra-caladea fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

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
# --add-opens lets SessionReusingFtpsClient prime the TLS session cache by reflection, which
# FTPS servers that mandate data-channel session resumption require (see that class). JPMS
# blocks reflection into sun.security.ssl (the session context) and sun.security.util (the
# MemoryCache backing it) by default on JDK 17; without both the client
# logs a warning and carries on, and only those servers fail.
ENTRYPOINT ["java", "--add-opens", "java.base/sun.security.ssl=ALL-UNNAMED", "--add-opens", "java.base/sun.security.util=ALL-UNNAMED", "-jar", "/app/app.jar"]

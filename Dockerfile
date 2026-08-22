# Use a lightweight OpenJDK 17 image
FROM eclipse-temurin:17-jdk

# Maintainer info
LABEL maintainer="nabeel.amd93@gmail.com"

# Set working directory
WORKDIR /app

# Install curl for health checks, ffmpeg for audio upload transcoding (ALAC -> AAC), and a
# headless LibreOffice for the Document Converter (JODConverter drives this same binary to do
# every actual conversion -- see DocumentConverterServiceImpl). Targeted components (writer/
# calc/impress/draw, not libreoffice-full) keep this from pulling in the GUI/help/every language
# pack; --no-install-recommends trims it further. Still a large layer (LibreOffice itself is
# a few hundred MB) -- that's the real cost of this feature, not something to optimize away.
#
# Fonts: eclipse-temurin ships with essentially none. Without them, LibreOffice silently
# substitutes a fallback font for every Word/Excel/PowerPoint file that references Arial, Times
# New Roman, Courier New, Calibri, or Cambria (the actual defaults in modern Office documents) --
# converted output then reflows/paginates differently than the original. fonts-liberation is the
# metric-compatible clone of the first three; fonts-crosextra-carlito/-caladea cover Calibri/
# Cambria; fonts-dejavu-core is the general fallback. Same fix RAAD-Converter's Dockerfile
# reaches for by bundling a Fonts/ folder -- these are the equivalent, freely-redistributable
# packages instead of shipping unverified third-party font binaries.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ffmpeg \
        libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress libreoffice-draw \
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

# Stage 1: Build stage
FROM maven:3.9-eclipse-temurin-25-alpine AS builder

# Set working directory
WORKDIR /build

# GitHub Packages credentials for Maven (passed at build time)
ARG GITHUB_USERNAME
ARG GITHUB_TOKEN

# Copy pom.xml
COPY pom.xml .

# Configure Maven settings.xml to access GitHub Packages
RUN mkdir -p /root/.m2 && \
    echo '<settings>' > /root/.m2/settings.xml && \
    echo '  <servers>' >> /root/.m2/settings.xml && \
    echo '    <server>' >> /root/.m2/settings.xml && \
    echo '      <id>github-lib-version</id>' >> /root/.m2/settings.xml && \
    echo "      <username>${GITHUB_USERNAME}</username>" >> /root/.m2/settings.xml && \
    echo "      <password>${GITHUB_TOKEN}</password>" >> /root/.m2/settings.xml && \
    echo '    </server>' >> /root/.m2/settings.xml && \
    echo '    <server>' >> /root/.m2/settings.xml && \
    echo '      <id>github-lib-version-releases</id>' >> /root/.m2/settings.xml && \
    echo "      <username>${GITHUB_USERNAME}</username>" >> /root/.m2/settings.xml && \
    echo "      <password>${GITHUB_TOKEN}</password>" >> /root/.m2/settings.xml && \
    echo '    </server>' >> /root/.m2/settings.xml && \
    echo '  </servers>' >> /root/.m2/settings.xml && \
    echo '</settings>' >> /root/.m2/settings.xml

# Download dependencies, uses caching if pom.xml doesnt change --> speedup
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application --> Jar file
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime stage
FROM eclipse-temurin:25-jre-alpine

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Set server port and model host url as env var's with default values
ENV SERVER_PORT=8080
ENV MODEL_HOST=http://model-service:8081

# Expose the port
EXPOSE ${SERVER_PORT}

# Run the application
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${SERVER_PORT} -jar app.jar"]
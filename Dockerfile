# Stage 1: Build
FROM eclipse-temurin:22-jdk-alpine AS build
WORKDIR /app

# Install dependencies separately to leverage Docker layer caching
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && \
    sed -i 's/\r$//' mvnw

# Download dependencies using a cache mount
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:22-jre-alpine
WORKDIR /app

# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# JVM flags for container awareness and performance
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "app.jar"]
# Stage 1: Build mit Maven und Java 25
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Dependencies cachen
COPY pom.xml .
RUN mvn dependency:go-offline

# Source-Code kopieren und JAR bauen
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Schlankes Runtime-Image mit Java 25
FROM eclipse-temurin:25-jre-alpine

# Non-root user for security hardening
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --chown=appuser:appgroup --from=build /app/target/aura-sentry-core-0.0.1-SNAPSHOT.jar app.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

COPY . .
# Repo may have been checked out on Windows, where the executable bit isn't preserved.
RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

# --- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# Documentation only - Render injects its own PORT, which application.properties'
# server.port=${PORT:8081} already honors. Not authoritative at runtime.
EXPOSE 8081

# Render doesn't set SPRING_PROFILES_ACTIVE for you; this buildathon deployment
# requires the demo profile (DemoSettlementVerifier/DemoDataSeeder), so default
# to it here while still letting Render override it if ever needed.
ENV SPRING_PROFILES_ACTIVE=demo

ENTRYPOINT ["sh", "-c", "java -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE}"]

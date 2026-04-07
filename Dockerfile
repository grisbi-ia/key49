# Key49 — Dockerfile JVM
# Build: docker build -t key49 .
# Run:   docker run -p 8080:8080 --env-file .env key49

# ── Stage 1: Build ──
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY key49-core/pom.xml key49-core/pom.xml
COPY key49-xml/pom.xml key49-xml/pom.xml
COPY key49-signer/pom.xml key49-signer/pom.xml
COPY key49-sri/pom.xml key49-sri/pom.xml
COPY key49-queue/pom.xml key49-queue/pom.xml
COPY key49-ride/pom.xml key49-ride/pom.xml
COPY key49-notify/pom.xml key49-notify/pom.xml
COPY key49-storage/pom.xml key49-storage/pom.xml
COPY key49-admin/pom.xml key49-admin/pom.xml
COPY key49-api/pom.xml key49-api/pom.xml
RUN mvn dependency:go-offline -B

COPY . .
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ──
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Crear usuario no-root
RUN addgroup -S key49 && adduser -S key49 -G key49

COPY --from=build /app/key49-api/target/quarkus-app/ /app/

USER key49

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]

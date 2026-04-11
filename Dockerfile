# Key49 — Dockerfile de Producción (Multi-stage, JVM)
# Build: docker build -t key49:latest .
# Run:   docker run -p 8080:8080 --env-file .env key49:latest

# ── Stage 1: Compilación con Maven ──
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Copiar solo POM para cache de dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar fuente y compilar
COPY src/ src/
RUN mvn clean package -DskipTests -B \
    && mv target/quarkus-app /quarkus-app

# ── Stage 2: Runtime JRE mínimo ──
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Paquetes mínimos: curl para healthcheck, tzdata para zona horaria
RUN apk add --no-cache curl tzdata \
    && cp /usr/share/zoneinfo/America/Guayaquil /etc/localtime \
    && echo "America/Guayaquil" > /etc/timezone \
    && apk del tzdata

# Usuario no-root
RUN addgroup -S key49 && adduser -S key49 -G key49

# Copiar artefactos Quarkus (fast-jar layout)
COPY --from=build --chown=key49:key49 /quarkus-app/ ./

USER key49

EXPOSE 8080

# JVM flags de producción
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/urandom \
    -Dquarkus.http.host=0.0.0.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/q/health/ready || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]

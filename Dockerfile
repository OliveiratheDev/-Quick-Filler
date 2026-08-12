FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B test package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        curl \
        fonts-dejavu-core \
        tesseract-ocr \
        tesseract-ocr-eng \
        tesseract-ocr-por \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system quickfiller \
    && useradd --system --gid quickfiller --home-dir /app quickfiller \
    && mkdir -p /app /data \
    && chown -R quickfiller:quickfiller /app /data

WORKDIR /app
COPY --from=build /build/target/quick-filler-1.0.0.jar app.jar

USER quickfiller
ENV PORT=8080 \
    TRANSCRIPTION_STORAGE_DIR=/data \
    TESSERACT_COMMAND=tesseract \
    TESSERACT_LANGUAGE=por+eng

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=25s --retries=4 \
    CMD curl --fail --silent http://localhost:8080/healthz || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

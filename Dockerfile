# ── Этап сборки ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Сначала копируем только pom.xml — Docker закеширует зависимости
# и не будет перекачивать их при каждом изменении кода
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Теперь копируем исходники и собираем
COPY src src/
RUN mvn clean package -DskipTests

# ── Финальный образ ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Устанавливаем ffmpeg и curl (нужен для скачивания yt-dlp)
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Создаём директории и пользователя
RUN useradd -m -U -d /app appuser \
    && mkdir -p /app/bin /app/temp /app/logs \
    && chown -R appuser:appuser /app

# Скачиваем yt-dlp в /app/bin — эта директория принадлежит appuser,
# поэтому YtDlpUpdater сможет обновлять бинарник без root-прав
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /app/bin/yt-dlp \
    && chmod +x /app/bin/yt-dlp \
    && chown appuser:appuser /app/bin/yt-dlp

# Копируем собранный jar
COPY --from=build /build/target/*.jar app.jar

# Копируем entrypoint
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh \
    && chown appuser:appuser /app/entrypoint.sh /app/app.jar

USER appuser

# --enable-preview нужен для Structured Concurrency (Java 25 preview)
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 --enable-preview"
ENV YT_DLP_PATH=/app/bin/yt-dlp
ENV FFMPEG_PATH=/usr/bin/ffmpeg
ENV TEMP_DIR=/app/temp

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]

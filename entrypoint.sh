#!/bin/sh
set -e

echo "=== YouTube Audio Bot ==="
echo "Java: $(java -version 2>&1 | head -1)"
echo "yt-dlp: $(${YT_DLP_PATH} --version 2>/dev/null || echo 'not found')"
echo "ffmpeg: $(ffmpeg -version 2>&1 | head -1)"

# Проверяем и обновляем yt-dlp при старте
echo "Checking yt-dlp updates..."
CURRENT=$(${YT_DLP_PATH} --version 2>/dev/null || echo "none")
LATEST=$(curl -s --connect-timeout 5 \
    https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest \
    | grep '"tag_name"' \
    | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/' 2>/dev/null || echo "")

if [ -n "$LATEST" ] && [ "$CURRENT" != "$LATEST" ]; then
    echo "Updating yt-dlp: $CURRENT -> $LATEST"
    curl -sL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o "${YT_DLP_PATH}.tmp" \
    && mv "${YT_DLP_PATH}.tmp" "${YT_DLP_PATH}" \
    && chmod +x "${YT_DLP_PATH}"
    echo "yt-dlp updated to $(${YT_DLP_PATH} --version)"
else
    echo "yt-dlp is up to date ($CURRENT)"
fi

echo "Starting application..."
exec java $JAVA_OPTS -jar /app/app.jar

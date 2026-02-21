package youtubebot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Вся конфигурация приложения из переменных окружения.
 * Record — неизменяемый, создаётся один раз при старте.
 */
public record AppConfig(
        String  botToken,
        String  botUsername,
        Set<Long> adminChatIds,
        String  webhookUrl,
        int     port,
        String  tempDir,
        String  ytDlpPath,
        String  ffmpegPath,
        long    maxFileSizeBytes,
        int     downloadTimeoutSeconds,
        String cookiesFile      // путь к файлу cookies, null если не задан

) {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    public static AppConfig fromEnv() {
        return new AppConfig(
                requireEnv("BOT_TOKEN"),
                requireEnv("BOT_USERNAME"),
                parseAdminIds(getEnv("BOT_ADMIN_CHATIDS", "")),
                requireEnv("WEBHOOK_URL"),
                Integer.parseInt(getEnv("PORT", "8080")),
                getEnv("TEMP_DIR", "/app/temp"),
                getEnv("YT_DLP_PATH", "/app/bin/yt-dlp"),
                getEnv("FFMPEG_PATH", "ffmpeg"),
                Long.parseLong(getEnv("MAX_FILE_SIZE_BYTES", String.valueOf(50L * 1024 * 1024))),
                Integer.parseInt(getEnv("DOWNLOAD_TIMEOUT_SECONDS", "600")),
                getEnv("COOKIES_FILE", null)
        );
    }

    public void validate() {
        if (!botToken.contains(":")) {
            throw new IllegalStateException("BOT_TOKEN has invalid format");
        }
        if (!webhookUrl.startsWith("https://")) {
            throw new IllegalStateException("WEBHOOK_URL must start with https://");
        }
        log.info("Config loaded: bot={}, port={}, adminCount={}",
                botUsername, port, adminChatIds.size());
    }

    public boolean isAdmin(long chatId) {
        return adminChatIds.contains(chatId);
    }

    private static Set<Long> parseAdminIds(String raw) {
        if (raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value.trim();
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }
}

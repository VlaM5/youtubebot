package youtubebot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Проверяет и обновляет бинарник yt-dlp.
 *
 * Стратегия: при старте и раз в 24 часа сравниваем текущую версию
 * с последним релизом на GitHub. Если есть обновление — скачиваем
 * бинарник напрямую с GitHub Releases.
 *
 * Бинарник лежит в /app/bin/yt-dlp — директория принадлежит appuser,
 * поэтому root-права не нужны.
 */
public class YtDlpUpdater {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUpdater.class);

    private static final String GITHUB_API =
            "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final String DOWNLOAD_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";

    private final String ytDlpPath;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("yt-dlp-updater").factory()
            );

    public YtDlpUpdater(String ytDlpPath) {
        this.ytDlpPath = ytDlpPath;
    }

    /** Вызывается при старте приложения в виртуальном потоке */
    public void checkAndUpdate() {
        try {
            String current = getCurrentVersion();
            String latest  = getLatestVersion();

            if (latest == null) {
                log.warn("Could not fetch latest yt-dlp version from GitHub");
                return;
            }

            log.info("yt-dlp: current={}, latest={}", current, latest);

            if (!latest.equals(current)) {
                log.info("Updating yt-dlp: {} → {}", current, latest);
                downloadBinary();
                log.info("yt-dlp updated to {}", getCurrentVersion());
            } else {
                log.info("yt-dlp is up to date");
            }
        } catch (Exception e) {
            // Не падаем — бот продолжит работу со старой версией
            log.error("yt-dlp update failed: {}", e.getMessage());
        }
    }

    /** Запускает проверку раз в 24 часа */
    public void scheduleDaily() {
        scheduler.scheduleAtFixedRate(
                this::checkAndUpdate,
                24, 24, TimeUnit.HOURS
        );
    }

    public String getCurrentVersion() {
        try {
            Process p = new ProcessBuilder(ytDlpPath, "--version")
                    .redirectErrorStream(true)
                    .start();
            String version = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return version.isEmpty() ? "unknown" : version;
        } catch (Exception e) {
            log.warn("Cannot get current yt-dlp version: {}", e.getMessage());
            return "unknown";
        }
    }

    private String getLatestVersion() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "YouTubeAudioBot")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            // Парсим "tag_name" без лишних зависимостей
            String body = response.body();
            int idx = body.indexOf("\"tag_name\"");
            if (idx == -1) return null;
            int start = body.indexOf('"', idx + 11) + 1;
            int end   = body.indexOf('"', start);
            return body.substring(start, end).trim();

        } catch (Exception e) {
            log.warn("GitHub API request failed: {}", e.getMessage());
            return null;
        }
    }

    private void downloadBinary() throws Exception {
        Path target = Path.of(ytDlpPath);
        Path tmp    = Path.of(ytDlpPath + ".tmp");

        Files.createDirectories(target.getParent());

        var request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOAD_URL))
                .header("User-Agent", "YouTubeAudioBot")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Download failed, HTTP " + response.statusCode());
        }

        // Пишем во временный файл, потом атомарно заменяем
        try (var in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        target.toFile().setExecutable(true);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}

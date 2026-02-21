package youtubebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import youtubebot.model.AudioFormat;
import youtubebot.model.UserSession;
import youtubebot.model.VideoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Ядро приложения — взаимодействие с yt-dlp и ffmpeg.
 *
 * Каждая загрузка запускается в отдельном виртуальном потоке.
 * Пока yt-dlp работает (блокирующий вызов), виртуальный поток паркуется
 * и не занимает OS-ресурсы.
 */
public class DownloadWorker {

    private static final Logger log = LoggerFactory.getLogger(DownloadWorker.class);

    private static final Pattern YOUTUBE_URL = Pattern.compile(
            "^https?://(?:www\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})(?:[&?].*)?$"
    );

    private final AppConfig        config;
    private final TelegramClient   telegram;
    private final ObjectMapper     json    = new ObjectMapper();
    private final ExecutorService  executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public DownloadWorker(AppConfig config, TelegramClient telegram) {
        this.config   = config;
        this.telegram = telegram;
    }

    // ── Валидация ──────────────────────────────────────────────────────────

    public boolean isValidYouTubeUrl(String url) {
        if (url == null || url.length() > 200) return false;
        if (!YOUTUBE_URL.matcher(url).matches()) return false;
        try {
            String host = new URI(url).getHost().toLowerCase();
            return host.equals("youtube.com")
                    || host.equals("www.youtube.com")
                    || host.equals("youtu.be");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // ── Метаданные ─────────────────────────────────────────────────────────

    /**
     * Получает метаданные видео через yt-dlp --dump-json.
     * Выбрасывает исключение если видео недоступно или превышен таймаут.
     */
    public VideoInfo getMetadata(String url) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of(
                config.ytDlpPath(),
                "--dump-json",
                "--no-warnings",
                "--no-playlist",
                "--no-check-certificate",
                "--extractor-args", "youtube:skip=dash",
                "--verbose"   // временно для диагностики, потом заменить на --quiet
        ));
        if (config.cookiesFile() != null) {
            cmd.add("--cookies");
            cmd.add(config.cookiesFile());
        }
        cmd.add(url);

        String output = runProcess(cmd, "metadata");
        return parseMetadata(output);
    }

    private VideoInfo parseMetadata(String jsonOutput) throws Exception {
        JsonNode root = json.readTree(jsonOutput);

        String title    = root.path("title").asText("Unknown");
        long   duration = root.path("duration").asLong(0);

        // Ищем лучший аудио-only формат
        JsonNode formats = root.path("formats");
        String   fmt     = "m4a";
        String   codec   = "aac";
        int      bitrate = 128;
        long     size    = -1;

        for (JsonNode f : formats) {
            boolean audioOnly = f.path("vcodec").asText().equals("none")
                    && !f.path("acodec").asText().equals("none");
            if (!audioOnly) continue;

            int br = f.path("abr").asInt(0);
            if (br <= 0) continue;

            if (br > bitrate) {
                bitrate = br;
                fmt     = f.path("ext").asText("m4a");
                codec   = f.path("acodec").asText("aac");
                size    = f.path("filesize").asLong(-1);
                if (size <= 0) size = f.path("filesize_approx").asLong(-1);
            }
        }

        return new VideoInfo(title, duration, fmt, codec, bitrate, size);
    }

    // ── Загрузка ───────────────────────────────────────────────────────────

    /**
     * Запускает загрузку в виртуальном потоке.
     * По завершении отправляет файл пользователю или сообщение об ошибке.
     */
    public void startAsync(UserSession session) {
        executor.submit(() -> {
            long chatId = session.chatId();
            Path tempFile = null;
            try {
                telegram.sendMessage(chatId, "⏳ Загружаю аудио...");
                tempFile = download(session);
                telegram.sendAudio(chatId, tempFile, session.videoInfo().title(),
                        session.selectedFormat().displayName());
                telegram.sendMessage(chatId, "✅ Готово!");
            } catch (Exception e) {
                log.error("Download failed for chatId={}", chatId, e);
                telegram.sendMessage(chatId, "❌ Ошибка: " + friendlyError(e));
            } finally {
                deleteQuietly(tempFile);
            }
        });
    }

    private Path download(UserSession session) throws Exception {
        VideoInfo   info   = session.videoInfo();
        AudioFormat format = session.selectedFormat();

        String ext = format.isOriginal() ? info.audioFormat() : "ogg";
        Path output = Files.createTempFile(
                Path.of(config.tempDir()), "yt_", "." + ext);

        List<String> cmd = buildDownloadCommand(session.url(), format, output);
        runProcess(cmd, "download");

        if (!Files.exists(output) || Files.size(output) == 0) {
            throw new RuntimeException("Файл не был создан или пуст");
        }
        if (Files.size(output) > config.maxFileSizeBytes()) {
            throw new RuntimeException("Файл превышает лимит Telegram (50 MB)");
        }
        return output;
    }

    private List<String> buildDownloadCommand(String url, AudioFormat format, Path output) {
        var cmd = new ArrayList<String>();
        cmd.add(config.ytDlpPath());
        cmd.add("--no-warnings");
        cmd.add("--no-playlist");
        cmd.add("--quiet");
        if (config.cookiesFile() != null) {
            cmd.add("--cookies");
            cmd.add(config.cookiesFile());
        }
        cmd.add("--ffmpeg-location");
        cmd.add(config.ffmpegPath());

        if (format.isOriginal()) {
            cmd.addAll(List.of("-f", "bestaudio", "-o", output.toString()));
        } else {
            // Скачиваем лучшее аудио и перекодируем в Opus через ffmpeg
            cmd.addAll(List.of(
                    "-f", "bestaudio",
                    "--postprocessor-args",
                    "ffmpeg:-c:a libopus -b:a %dk".formatted(format.bitrateKbps()),
                    "-x", "--audio-format", "vorbis",  // контейнер ogg для opus
                    "-o", output.toString()
            ));
        }
        cmd.add(url);
        return cmd;
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────

    private String runProcess(List<String> cmd, String stage) throws Exception {
        log.debug("[{}] Running: {}", stage, String.join(" ", cmd));

        var pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true);
        Files.createDirectories(Path.of(config.tempDir()));

        Process process = pb.start();
        var output = new StringBuilder();

        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                log.debug("[{}] {}", stage, line);
            }
        }

        boolean finished = process.waitFor(config.downloadTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Превышено время ожидания (%d сек)".formatted(
                    config.downloadTimeoutSeconds()));
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("yt-dlp завершился с ошибкой. Вывод:\n" + output);
        }

        return output.toString().trim();
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "Неизвестная ошибка";
        if (msg.contains("Private video"))   return "Видео является приватным";
        if (msg.contains("not available"))   return "Видео недоступно в вашем регионе";
        if (msg.contains("тайм") || msg.contains("timeout"))
            return "Превышено время ожидания. Попробуйте видео покороче";
        if (msg.contains("50 MB"))           return "Видео слишком длинное для загрузки";
        return "Не удалось загрузить видео. Попробуйте позже";
    }

    private void deleteQuietly(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (Exception e) { log.warn("Could not delete temp file: {}", file); }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

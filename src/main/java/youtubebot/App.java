package youtubebot;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Точка входа. Собирает все зависимости вручную и запускает сервер.
 * Никакого DI-фреймворка — для 8 классов это просто не нужно.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        var config = AppConfig.fromEnv();
        config.validate();

        // Декодируем cookies из переменной окружения и пишем во временный файл
        String cookiesB64 = System.getenv("YOUTUBE_COOKIES_BASE64");
        if (cookiesB64 != null && !cookiesB64.isBlank()) {
            try {
                Path cookiesFile = Path.of(config.tempDir() + "/cookies.txt");
                Files.createDirectories(cookiesFile.getParent());
                Files.write(cookiesFile, Base64.getDecoder().decode(cookiesB64));
                log.info("Cookies file written to {}", cookiesFile);
                config = new AppConfig(
                        config.botToken(), config.botUsername(), config.adminChatIds(),
                        config.webhookUrl(), config.port(), config.tempDir(),
                        config.ytDlpPath(), config.ffmpegPath(),
                        config.maxFileSizeBytes(), config.downloadTimeoutSeconds(),
                        cookiesFile.toString()
                );
            } catch (Exception e) {
                log.error("Failed to write cookies file: {}", e.getMessage());
            }
        }



        // Сборка зависимостей
        var telegramClient = new TelegramClient(config.botToken());
        var sessionStore   = new SessionStore();
        var ytDlpUpdater   = new YtDlpUpdater(config.ytDlpPath());
        var downloadWorker = new DownloadWorker(config, telegramClient);
        var botHandler     = new BotHandler(config, telegramClient, sessionStore, downloadWorker);
        var webHandler     = new WebHandler(config, sessionStore, downloadWorker);

        // Обновляем yt-dlp при старте, не блокируя запуск сервера
        Thread.ofVirtual().start(ytDlpUpdater::checkAndUpdate);

        // Запуск планировщика обновлений раз в сутки
        ytDlpUpdater.scheduleDaily();

        // HTTP сервер
        var app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/web");  // статика веб-интерфейса из resources/web/
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        // Telegram webhook
        app.post("/webhook", botHandler::onUpdate);

        // REST API для веб-версии
        app.post("/api/download",      webHandler::startDownload);
        app.get("/api/status/{id}",    webHandler::getStatus);

        // Healthcheck для Railway
        app.get("/health", ctx -> ctx.result("OK"));

        app.start(config.port());

        // Регистрируем webhook в Telegram
        telegramClient.setWebhook(config.webhookUrl() + "/webhook");

        log.info("Bot started on port {}", config.port());

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down...");
            app.stop();
            downloadWorker.shutdown();
            ytDlpUpdater.shutdown();
        }));
    }
}

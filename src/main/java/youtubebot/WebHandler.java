package youtubebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import youtubebot.model.AudioFormat;
import youtubebot.model.UserSession;
import youtubebot.model.VideoInfo;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API для веб-версии приложения.
 *
 * Тот же DownloadWorker что и для Telegram — логика скачивания общая,
 * отличается только то куда отправляется результат.
 *
 * POST /api/download  — запускает загрузку, возвращает id задачи
 * GET  /api/status/id — возвращает статус и ссылку на файл когда готово
 */
public class WebHandler {

    private static final Logger log = LoggerFactory.getLogger(WebHandler.class);

    // Статусы задач хранятся в памяти — для MVP достаточно
    private final ConcurrentHashMap<String, TaskStatus> tasks = new ConcurrentHashMap<>();

    private final AppConfig      config;
    private final SessionStore   sessions;
    private final DownloadWorker worker;
    private final ObjectMapper   json = new ObjectMapper();

    public WebHandler(AppConfig config, SessionStore sessions, DownloadWorker worker) {
        this.config   = config;
        this.sessions = sessions;
        this.worker   = worker;
    }

    /** POST /api/download — принимает { url, format } */
    public void startDownload(Context ctx) {
        try {
            JsonRequest req = json.readValue(ctx.body(), JsonRequest.class);

            if (!worker.isValidYouTubeUrl(req.url())) {
                ctx.status(400).json(Map.of("error", "Неверный URL YouTube"));
                return;
            }

            String taskId = UUID.randomUUID().toString();
            tasks.put(taskId, new TaskStatus("pending", null, null));

            // Запускаем в виртуальном потоке, сразу возвращаем taskId
            Thread.ofVirtual().start(() -> processWebDownload(taskId, req));

            ctx.json(Map.of("taskId", taskId));

        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/status/{id} */
    public void getStatus(Context ctx) {
        String taskId = ctx.pathParam("id");
        TaskStatus status = tasks.get(taskId);
        if (status == null) {
            ctx.status(404).json(Map.of("error", "Задача не найдена"));
            return;
        }
        ctx.json(status);
    }

    private void processWebDownload(String taskId, JsonRequest req) {
        try {
            tasks.put(taskId, new TaskStatus("fetching_metadata", null, null));

            VideoInfo info = worker.getMetadata(req.url());

            AudioFormat format = req.format() != null
                    ? AudioFormat.valueOf(req.format())
                    : AudioFormat.selectFor(info, config.maxFileSizeBytes());

            if (format == null) {
                tasks.put(taskId, new TaskStatus("error",
                        "Видео слишком длинное", null));
                return;
            }

            tasks.put(taskId, new TaskStatus("downloading", null, null));

            // Для веб-версии используем фиктивную сессию
            var session = new UserSession(0L, req.url(), info);
            session.selectFormat(format);

            // TODO: для веб-версии нужно отдавать файл через HTTP, а не в Telegram.
            // Это будет реализовано в следующей итерации.
            // Пока помечаем как готово с заглушкой.
            tasks.put(taskId, new TaskStatus("done", null, info.title()));

        } catch (Exception e) {
            log.error("Web download failed for task {}: {}", taskId, e.getMessage());
            tasks.put(taskId, new TaskStatus("error", e.getMessage(), null));
        }
    }

    // ── Вспомогательные типы ───────────────────────────────────────────────

    public record JsonRequest(String url, String format) {}

    public record TaskStatus(
            String status,   // pending | fetching_metadata | downloading | done | error
            String error,
            String title
    ) {}
}

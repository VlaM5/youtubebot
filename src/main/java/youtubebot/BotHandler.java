package youtubebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import youtubebot.model.AudioFormat;
import youtubebot.model.UserSession;
import youtubebot.model.VideoInfo;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Обрабатывает входящие Telegram updates (сообщения и callback-кнопки).
 * Telegram шлёт POST /webhook с JSON-телом при каждом событии.
 */
public class BotHandler {

    private static final Logger log = LoggerFactory.getLogger(BotHandler.class);

    private final AppConfig      config;
    private final TelegramClient telegram;
    private final SessionStore   sessions;
    private final DownloadWorker worker;
    private final ObjectMapper   json = new ObjectMapper();

    public BotHandler(AppConfig config, TelegramClient telegram,
                      SessionStore sessions, DownloadWorker worker) {
        this.config   = config;
        this.telegram = telegram;
        this.sessions = sessions;
        this.worker   = worker;
    }

    /** Точка входа — Javalin вызывает этот метод при POST /webhook */
    public void onUpdate(Context ctx) {
        ctx.status(200); // Telegram требует 200 как можно быстрее
        try {
            JsonNode update = json.readTree(ctx.body());
            if (update.has("message")) {
                handleMessage(update.get("message"));
            } else if (update.has("callback_query")) {
                handleCallback(update.get("callback_query"));
            }
        } catch (Exception e) {
            log.error("Error processing update: {}", e.getMessage(), e);
        }
    }

    // ── Сообщения ──────────────────────────────────────────────────────────

    private void handleMessage(JsonNode message) {
        if (!message.has("text")) return;

        long   chatId = message.get("chat").get("id").asLong();
        String text   = message.get("text").asText().trim();

        log.debug("Message from {}: {}", chatId, text);

        if (text.startsWith("/start")) {
            sendWelcome(chatId);
        } else if (text.startsWith("/help")) {
            sendHelp(chatId);
        } else if (text.startsWith("/versions") && config.isAdmin(chatId)) {
            sendVersions(chatId);
        } else if (worker.isValidYouTubeUrl(text)) {
            handleUrl(chatId, text);
        } else {
            telegram.sendMessage(chatId, "Пожалуйста, отправьте ссылку на YouTube видео.");
        }
    }

    private void handleUrl(long chatId, String url) {
        Thread.ofVirtual().start(() -> {
            try {
                telegram.sendMessage(chatId, "🔍 Получаю информацию о видео...");
                VideoInfo info = worker.getMetadata(url);

                AudioFormat selected = AudioFormat.selectFor(info, config.maxFileSizeBytes());
                if (selected == null) {
                    telegram.sendMessage(chatId,
                            "❌ Видео слишком длинное — даже с максимальным сжатием файл превысит 50 MB.");
                    return;
                }

                sessions.put(new UserSession(chatId, url, info));
                sendFormatSelection(chatId, info);

            } catch (Exception e) {
                log.error("Error getting metadata for {}: {}", url, e.getMessage());
                telegram.sendMessage(chatId, "❌ Не удалось получить информацию о видео. " +
                        "Проверьте ссылку или попробуйте позже.");
            }
        });
    }

    // ── Callback кнопки ────────────────────────────────────────────────────

    private void handleCallback(JsonNode callback) {
        long   chatId = callback.get("message").get("chat").get("id").asLong();
        String data   = callback.get("data").asText();

        log.debug("Callback from {}: {}", chatId, data);

        if (data.startsWith("fmt:")) {
            handleFormatSelected(chatId, data.substring(4));
        }
    }

    private void handleFormatSelected(long chatId, String formatName) {
        UserSession session = sessions.get(chatId);
        if (session == null || session.isExpired()) {
            telegram.sendMessage(chatId, "Сессия устарела. Отправьте ссылку повторно.");
            sessions.remove(chatId);
            return;
        }
        if (session.state() == UserSession.State.DOWNLOADING) {
            telegram.sendMessage(chatId, "Загрузка уже идёт, подождите.");
            return;
        }

        try {
            AudioFormat format = AudioFormat.valueOf(formatName);
            session.selectFormat(format);
            worker.startAsync(session);
        } catch (IllegalArgumentException e) {
            telegram.sendMessage(chatId, "Неизвестный формат. Отправьте ссылку повторно.");
            sessions.remove(chatId);
        }
    }

    // ── Формирование сообщений ─────────────────────────────────────────────

    private void sendFormatSelection(long chatId, VideoInfo info) {
        String text = """
                🎵 *%s*
                ⏱ Длительность: %s
                
                Выберите формат:""".formatted(
                escapeMarkdown(info.title()),
                info.formattedDuration()
        );

        // Строим кнопки только для форматов которые вписываются в лимит
        var buttons = new ArrayList<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>();
        for (AudioFormat fmt : AudioFormat.values()) {
            long size = (fmt.isOriginal() && info.fileSizeBytes() > 0)
                    ? info.fileSizeBytes()
                    : fmt.estimateSizeBytes(info.durationSeconds(), info.audioBitrateKbps());

            if (size <= config.maxFileSizeBytes()) {
                String label = fmt.isOriginal()
                        ? "⭐ %s (%s)".formatted(fmt.displayName(), info.formattedSize())
                        : "📦 %s (~%.0f MB)".formatted(fmt.displayName(),
                        size / (1024.0 * 1024.0));
                buttons.add(TelegramClient.button(label, "fmt:" + fmt.name()));
            }
        }

        if (buttons.isEmpty()) {
            telegram.sendMessage(chatId, "❌ Видео слишком длинное для загрузки.");
            return;
        }

        // Каждая кнопка на отдельной строке
        var rows = buttons.stream()
                .map(List::of)
                .toList();
        var keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        telegram.sendMessageWithKeyboard(chatId, text, keyboard);
    }

    private void sendWelcome(long chatId) {
        telegram.sendMessage(chatId, """
                Привет! 👋
                
                Я извлекаю аудио из YouTube видео.
                Просто отправь мне ссылку — и получишь аудиофайл.
                
                Поддерживаю форматы Opus и AAC (оригинальное качество YouTube).
                Конвертация в MP3 не нужна — современные устройства воспроизводят эти форматы нативно.
                """);
    }

    private void sendHelp(long chatId) {
        String text = """
                📖 *Как использовать:*
                1. Отправь ссылку на YouTube видео
                2. Выбери формат (оригинал или сжатый Opus)
                3. Дождись файла
                
                *Ограничения:*
                — Максимальный размер файла: 50 MB
                — При превышении предлагаю Opus с меньшим битрейтом
                """;
        if (config.isAdmin(chatId)) {
            text += "\n*Команды администратора:*\n/versions — версии компонентов";
        }
        telegram.sendMessage(chatId, text);
    }

    private void sendVersions(long chatId) {
        Thread.ofVirtual().start(() -> {
            try {
                String ytDlpVersion = getProcessOutput(
                        List.of(config.ytDlpPath(), "--version"));
                String ffmpegVersion = getProcessOutput(
                        List.of(config.ffmpegPath(), "-version"))
                        .lines().findFirst().orElse("unknown");

                telegram.sendMessage(chatId, """
                        🔧 Версии компонентов:
                        
                        yt-dlp: %s
                        ffmpeg: %s
                        Java:   %s
                        """.formatted(ytDlpVersion, ffmpegVersion,
                        System.getProperty("java.version")));
            } catch (Exception e) {
                telegram.sendMessage(chatId, "Ошибка при получении версий: " + e.getMessage());
            }
        });
    }

    private String getProcessOutput(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out;
    }

    private String escapeMarkdown(String text) {
        return text.replace("_", "\\_").replace("*", "\\*")
                .replace("[", "\\[").replace("`", "\\`");
    }
}

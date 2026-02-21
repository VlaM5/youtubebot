package youtubebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
//import org.telegram.telegrambots.meta.api.methods.webhooks.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.file.Path;
import java.util.List;

/**
 * Тонкая обёртка над telegrambots для отправки сообщений и файлов.
 * Не содержит бизнес-логики — только транспорт.
 */
public class TelegramClient extends DefaultAbsSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final String botToken;

    public TelegramClient(String botToken) {
        super(new DefaultBotOptions());
        this.botToken = botToken;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    // ── Отправка сообщений ─────────────────────────────────────────────────

    public void sendMessage(long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (Exception e) {
            log.error("Failed to send message to {}: {}", chatId, e.getMessage());
        }
    }

    public void sendMessageWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
        } catch (Exception e) {
            log.error("Failed to send message with keyboard to {}: {}", chatId, e.getMessage());
        }
    }

    public void editMessage(long chatId, int messageId, String newText) {
        try {
            execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(newText)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to edit message {}: {}", messageId, e.getMessage());
        }
    }

    // ── Отправка аудио ─────────────────────────────────────────────────────

    public void sendAudio(long chatId, Path audioFile, String title, String caption) {
        try {
            execute(SendAudio.builder()
                    .chatId(chatId)
                    .audio(new InputFile(audioFile.toFile()))
                    .title(title)
                    .caption(caption)
                    .performer("YouTube Audio")
                    .build());
        } catch (Exception e) {
            log.error("Failed to send audio to {}: {}", chatId, e.getMessage());
            sendMessage(chatId, "❌ Не удалось отправить файл: " + e.getMessage());
        }
    }

    // ── Клавиатуры ─────────────────────────────────────────────────────────

    public static InlineKeyboardMarkup singleRowKeyboard(List<InlineKeyboardButton> buttons) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(buttons)
                .build();
    }

    public static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    // ── Webhook ────────────────────────────────────────────────────────────

    public void setWebhook(String webhookUrl) {
        try {
            execute(SetWebhook.builder()
                    .url(webhookUrl)
                    .build());
            log.info("Webhook set to: {}", webhookUrl);
        } catch (Exception e) {
            log.error("Failed to set webhook: {}", e.getMessage());
        }
    }
}

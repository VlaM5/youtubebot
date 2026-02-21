package youtubebot;

import youtubebot.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Хранилище пользовательских сессий в памяти.
 * Автоматически удаляет устаревшие сессии каждые 15 минут.
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("session-cleaner").factory()
            );

    public SessionStore() {
        cleaner.scheduleAtFixedRate(this::removeExpired, 15, 15, TimeUnit.MINUTES);
    }

    public void put(UserSession session) {
        sessions.put(session.chatId(), session);
    }

    public UserSession get(long chatId) {
        return sessions.get(chatId);
    }

    public void remove(long chatId) {
        sessions.remove(chatId);
    }

    private void removeExpired() {
        int before = sessions.size();
        sessions.values().removeIf(UserSession::isExpired);
        int removed = before - sessions.size();
        if (removed > 0) log.debug("Removed {} expired sessions", removed);
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}

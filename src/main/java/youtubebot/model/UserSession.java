package youtubebot.model;

/**
 * Состояние сессии пользователя между шагами диалога.
 *
 * Сессия живёт в памяти (SessionStore). При рестарте контейнера теряется —
 * это приемлемо: пользователь просто отправит ссылку повторно.
 *
 * Record здесь не подходит — нам нужно менять поле selectedFormat
 * после того как пользователь выбрал качество.
 */
public class UserSession {

    public enum State {
        WAITING_FORMAT_SELECTION,  // показали метаданные, ждём выбора формата
        DOWNLOADING                // загрузка идёт
    }

    private final long      chatId;
    private final String    url;
    private final VideoInfo videoInfo;
    private final long      createdAt;

    private State       state = State.WAITING_FORMAT_SELECTION;
    private AudioFormat selectedFormat;

    public UserSession(long chatId, String url, VideoInfo videoInfo) {
        this.chatId    = chatId;
        this.url       = url;
        this.videoInfo = videoInfo;
        this.createdAt = System.currentTimeMillis();
    }

    public long      chatId()         { return chatId; }
    public String    url()            { return url; }
    public VideoInfo videoInfo()      { return videoInfo; }
    public State     state()          { return state; }
    public AudioFormat selectedFormat(){ return selectedFormat; }
    public long      createdAt()      { return createdAt; }

    public void selectFormat(AudioFormat format) {
        this.selectedFormat = format;
        this.state = State.DOWNLOADING;
    }

    /** Сессия устарела если висит больше 30 минут без действий */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > 30 * 60 * 1000L;
    }
}

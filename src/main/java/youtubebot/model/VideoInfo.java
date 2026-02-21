package youtubebot.model;

/**
 * Метаданные видео полученные от yt-dlp.
 * Record — неизменяемый, создаётся один раз и передаётся по всему пайплайну.
 */
public record VideoInfo(
        String title,
        long   durationSeconds,
        String audioFormat,    // "opus", "m4a" — что YouTube отдаёт нативно
        String audioCodec,     // "opus", "aac"
        int    audioBitrateKbps,
        long   fileSizeBytes   // реальный размер из yt-dlp, -1 если неизвестен
) {
    /** Человекочитаемая длительность, например "1:23:45" */
    public String formattedDuration() {
        long h = durationSeconds / 3600;
        long m = (durationSeconds % 3600) / 60;
        long s = durationSeconds % 60;
        if (h > 0) return "%d:%02d:%02d".formatted(h, m, s);
        return "%d:%02d".formatted(m, s);
    }

    /** Размер в мегабайтах, округлённый до одного знака */
    public String formattedSize() {
        if (fileSizeBytes <= 0) return "неизвестен";
        return "%.1f MB".formatted(fileSizeBytes / (1024.0 * 1024.0));
    }
}

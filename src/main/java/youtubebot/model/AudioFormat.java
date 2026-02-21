package youtubebot.model;

/**
 * Варианты форматов аудио.
 *
 * Философия: не конвертируем в MP3. YouTube хранит аудио в Opus или AAC —
 * оба поддерживаются всеми современными устройствами. Конвертация lossy→lossy
 * только ухудшает качество и тратит CPU.
 *
 * Если оригинал не влезает в 50 MB — перекодируем в Opus с меньшим битрейтом.
 * Opus при 48 kbps субъективно лучше MP3 при 128 kbps.
 */
public enum AudioFormat {

    /** Оригинальный поток без перекодирования — лучшее качество */
    ORIGINAL("Оригинальное качество", null, -1),

    /** Opus 96 kbps — хорошее качество, файл поменьше */
    OPUS_96("Opus 96 kbps", "libopus", 96),

    /** Opus 64 kbps — среднее качество для длинных видео */
    OPUS_64("Opus 64 kbps", "libopus", 64),

    /** Opus 48 kbps — максимальное сжатие, для очень длинных видео */
    OPUS_48("Opus 48 kbps", "libopus", 48);

    private final String displayName;
    private final String ffmpegCodec;   // null для ORIGINAL
    private final int    bitrateKbps;   // -1 для ORIGINAL

    AudioFormat(String displayName, String ffmpegCodec, int bitrateKbps) {
        this.displayName  = displayName;
        this.ffmpegCodec  = ffmpegCodec;
        this.bitrateKbps  = bitrateKbps;
    }

    public String displayName()  { return displayName; }
    public String ffmpegCodec()  { return ffmpegCodec; }
    public int    bitrateKbps()  { return bitrateKbps; }
    public boolean isOriginal()  { return this == ORIGINAL; }

    /**
     * Оценивает размер файла в байтах для данной длительности.
     * Для оригинала использует реальный битрейт из VideoInfo.
     */
    public long estimateSizeBytes(long durationSeconds, int originalBitrateKbps) {
        int kbps = isOriginal() ? originalBitrateKbps : bitrateKbps;
        return durationSeconds * kbps * 1000L / 8;
    }

    /**
     * Выбирает подходящий формат: сначала пробует оригинал,
     * потом последовательно более сжатые варианты.
     * Возвращает null если ничто не вписывается в лимит.
     */
    public static AudioFormat selectFor(VideoInfo info, long maxSizeBytes) {
        for (AudioFormat fmt : values()) {
            // Для оригинала используем реальный размер если известен
            long size = (fmt.isOriginal() && info.fileSizeBytes() > 0)
                    ? info.fileSizeBytes()
                    : fmt.estimateSizeBytes(info.durationSeconds(), info.audioBitrateKbps());

            if (size <= maxSizeBytes) return fmt;
        }
        return null; // видео слишком длинное даже для минимального битрейта
    }
}

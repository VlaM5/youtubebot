# YouTube Audio Bot

Telegram-бот для извлечения аудио из YouTube видео.


---

## Что умеет

- Принимает ссылку на YouTube видео через Telegram
- Извлекает аудио в оригинальном качестве (Opus/AAC) без перекодирования
- Если файл не вписывается в лимит Telegram (50 MB) — перекодирует в Opus с пониженным битрейтом
- Автоматически обновляет yt-dlp при старте и раз в сутки через GitHub Releases API.

---

## Структура проекта

```
src/main/java/youtubebot/
  ├── App.java                — точка входа, DI вручную, Javalin
  ├── AppConfig.java          — конфигурация из env
  ├── BotHandler.java         — обработка Telegram updates (webhook)
  ├── DownloadWorker.java     — скачивание и обработка аудио через yt-dlp
  ├── TelegramClient.java     — Telegram Bot API
  ├── YtDlpUpdater.java       — автообновление бинарника yt-dlp с GitHub Releases
  ├── SessionStore.java       — хранение пользовательских сессий в памяти
  └── model/
        ├── VideoInfo.java    — record: метаданные видео
        ├── UserSession.java  — record: состояние сессии пользователя
        └── AudioFormat.java  — enum: OPUS_ORIGINAL, AAC_ORIGINAL, OPUS_COMPRESSED

src/main/resources/
  └── logback.xml             — настройки логирования

```

---

## Конфигурация

Все параметры через переменные окружения (Railway их поддерживает нативно):

| Переменная          | Описание                              | Пример                        |
|---------------------|---------------------------------------|-------------------------------|
| `BOT_TOKEN`         | Токен от BotFather                    | `1234567890:ABCdef...`        |
| `BOT_USERNAME`      | Имя бота без @                        | `my_youtube_bot`              |
| `BOT_ADMIN_CHATIDS` | ID администраторов через запятую      | `123456789,987654321`         |
| `WEBHOOK_URL`       | Публичный URL приложения              | `https://app.up.railway.app`  |
| `PORT`              | Порт HTTP сервера (Railway ставит сам)| `8080`                        |
| `TEMP_DIR`          | Директория для временных файлов       | `/app/temp`                   |
| `YT_DLP_PATH`       | Путь к бинарнику yt-dlp               | `/app/bin/yt-dlp`             |

---

## Deploy
```
mvn clean package
curl "https://api.telegram.org/bot{TOKEN}/setWebhook?url=https://your.domain/webhook"
```
Docker-образ включает yt-dlp и ffmpeg. Переменные окружения — через хостинг или .env (не коммитить).

---
## Ограничения

- Максимальный размер файла: 50 MB (лимит Telegram Bot API)
- Максимальная длительность видео: до 4 часов (зависит от битрейта)
- Количество одновременных загрузок: зависит от хостинга, ограничений CPU и памяти контейнера

---

## Стек

- Java 21 (Virtual Threads в App.java, BotHandler.java, DownloadWorker.java, YtDlpUpdater.java. Везде где Thread.ofVirtual() и Executors.newVirtualThreadPerTaskExecutor().
- Javalin 6.x (HTTP сервер, регистрирует маршруты /webhook, /api/download, /api/status, /health.)
- telegrambots 6.9.7.1 (Telegram Bot API)
- Jackson ( DownloadWorker.java, метод parseMetadata(). Парсит JSON который возвращает yt-dlp с метаданными видео - название, длительность, форматы).)
- yt-dlp (скачивание видео,DownloadWorker.java, методы getMetadata() и buildDownloadCommand(). Запускается как внешний процесс через ProcessBuilder.)
- ffmpeg (перекодирование в Opus при необходимости, используется косвенно через yt-dlp, через --ffmpeg-location, не используется самостоятельно — только если yt-dlp вызывает его сам для перекодирования.)
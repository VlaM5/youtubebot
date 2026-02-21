# YouTube Audio Bot

Telegram-бот и веб-приложение для извлечения аудио из YouTube видео.


---

## Что умеет

- Принимает ссылку на YouTube видео через Telegram или веб-интерфейс
- Извлекает аудио в оригинальном качестве (Opus/AAC) без перекодирования
- Если файл не вписывается в лимит Telegram (50 MB) — перекодирует в Opus с пониженным битрейтом вместо конвертации в MP3
- Автоматически обновляет yt-dlp при старте и раз в сутки

---

## Структура проекта

```
src/main/java/youtubebot/
  ├── App.java                — точка входа, сборка зависимостей, запуск
  ├── BotHandler.java         — обработка Telegram updates (webhook)
  ├── WebHandler.java         — обработка запросов веб-интерфейса
  ├── DownloadWorker.java     — скачивание и обработка аудио через yt-dlp
  ├── TelegramClient.java     — отправка сообщений и файлов в Telegram
  ├── YtDlpUpdater.java       — проверка и обновление бинарника yt-dlp
  ├── SessionStore.java       — хранение пользовательских сессий в памяти
  └── model/
        ├── VideoInfo.java    — record: метаданные видео
        ├── UserSession.java  — record: состояние сессии пользователя
        └── AudioFormat.java  — enum: OPUS_ORIGINAL, AAC_ORIGINAL, OPUS_COMPRESSED

src/main/resources/
  ├── logback.xml             — настройки логирования
  └── web/                    — статические файлы веб-интерфейса
        ├── index.html
        └── app.js
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



## Ограничения

- Максимальный размер файла: 50 MB (лимит Telegram Bot API)
- Максимальная длительность видео: до 4 часов (зависит от битрейта)
- Количество одновременных загрузок: зависит от хостинга, ограничений CPU и памяти контейнера

---

## Технологии

- Java 25 с Virtual Threads и Structured Concurrency
- Javalin 6.x (HTTP сервер)
- telegrambots 6.9.7.1 (Telegram Bot API)
- Jackson (JSON)
- yt-dlp (скачивание видео)
- ffmpeg (перекодирование в Opus при необходимости)

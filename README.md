# Kettlebell Training Bot

Telegram-бот для персонализированных тренировок с гирями, использующий AI для адаптивного планирования.

## Технологии

- Kotlin 1.9.22
- Ktor 2.3.8
- MongoDB (KMongo)
- OpenAI API (GPT-4o, Whisper)
- Koin (Dependency Injection)
- Kotest (Testing)

## Настройка

1. Установите переменные окружения:
   ```bash
   export TELEGRAM_BOT_TOKEN=your_bot_token
   export OPENAI_API_KEY=your_openai_key
   export MONGODB_URI=mongodb://localhost:27017
   export MONGODB_DATABASE_NAME=kettlebell_db
   export FREE_MONTHLY_LIMIT=10
   ```

2. Для локальной разработки с Telegram webhook используйте ngrok:
   ```bash
   ngrok http 8080
   ```

3. Зарегистрируйте webhook:
   ```bash
   curl -X POST "https://api.telegram.org/bot<YOUR_BOT_TOKEN>/setWebhook?url=https://<ngrok_url>/webhook/<YOUR_BOT_TOKEN>"
   ```

## Запуск

```bash
./gradlew run
```

## Тестирование

```bash
./gradlew test
```


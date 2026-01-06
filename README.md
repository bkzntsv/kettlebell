# Kettlebell Training Bot

Telegram bot for personalized kettlebell training using AI for adaptive planning.

## Tech Stack

- Kotlin 1.9.22
- Ktor 2.3.8
- MongoDB (KMongo)
- OpenAI API (GPT-4o, Whisper)
- Koin (Dependency Injection)
- Kotest (Testing)

## Setup

1. Set environment variables:
   ```bash
   export TELEGRAM_BOT_TOKEN=your_bot_token
   export OPENAI_API_KEY=your_openai_key
   export MONGODB_URI=mongodb://localhost:27017
   export MONGODB_DATABASE_NAME=kettlebell_db
   export FREE_MONTHLY_LIMIT=10
   ```

2. For local development with Telegram webhook use ngrok:
   ```bash
   ngrok http 8080
   ```

3. Register webhook:
   ```bash
   curl -X POST "https://api.telegram.org/bot<YOUR_BOT_TOKEN>/setWebhook?url=https://<ngrok_url>/webhook/<YOUR_BOT_TOKEN>"
   ```

## Run

```bash
./gradlew run
```

## Testing

```bash
./gradlew test
```

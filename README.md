# Kettlebell Training

![Build Status](https://github.com/bkzntsv/kettlebell/actions/workflows/ci.yml/badge.svg)

AI-powered service for personalized kettlebell training with adaptive workout planning and feedback analysis.

[**Start Training**](https://t.me/giryatrener_bot)

## Key Features

- **Personalized Onboarding**: Tailors the experience based on your available equipment (specific kettlebell weights), fitness experience, and goals.
- **AI Workout Generation**: Creates unique workout plans adapted to your profile and past performance.
- **Voice Feedback**: Supports voice messages for workout feedback - just talk about how it went.
- **Adaptive Progression**: Analyzes your feedback (RPE, difficulty, technique) to adjust future training loads and intensity.
- **History Tracking**: Keeps a log of your completed workouts for progress monitoring.

## How It Works

1. **Start**: Complete a quick onboarding to set up your profile and equipment.
2. **Train**: Request a new workout via `/workout`. The bot generates a plan based on your current state.
3. **Track**: Mark the workout as started and finished using interactive buttons.
4. **Feedback**: detailed feedback via text or voice. The AI analyzes this to understand your performance.
5. **Progress**: The next workout will be adjusted based on this feedback.

## Commands

- `/start` - Start the bot or resume onboarding.
- `/workout` - Generate a new workout.
- `/profile` - View and edit your settings (equipment, goals, etc.).
- `/history` - View your training history.
- `/help` - Show available commands.

## Tech Stack

- **Core**: Kotlin, Ktor
- **AI**: OpenAI API (GPT, Whisper)
- **Data**: MongoDB (KMongo)
- **Infrastructure**: Docker

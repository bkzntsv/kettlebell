FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/install/kettlebell-training-bot /app
ENTRYPOINT ["/app/bin/kettlebell-training-bot"]

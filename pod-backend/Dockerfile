# syntax=docker/dockerfile:1
# Builds pod-backend's jar, then runs it on a JRE as an unprivileged user. Configured by
# environment variables (see deploy/.env.example); nothing secret is baked into the image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline -DskipTests || true
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
    && cp "$(find target -maxdepth 1 -name '*.jar' ! -name 'original-*' | head -1)" /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

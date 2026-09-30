# syntax=docker/dockerfile:1
# The Code-step service. Runs as root only to start each script as its own throwaway user id
# (setpriv, from util-linux); deploy/docker-compose.yml drops every other capability, gives it no
# network except to pod-processor, and a read-only filesystem with a small /tmp.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline -DskipTests || true
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN command -v setpriv
COPY --from=build /src/target/pod-sandbox.jar /opt/pod-sandbox.jar
RUN chmod 0644 /opt/pod-sandbox.jar
ENV SANDBOX_JAR=/opt/pod-sandbox.jar SANDBOX_ISOLATION=uid SANDBOX_PORT=8090
EXPOSE 8090
ENTRYPOINT ["java", "-Xmx256m", "-XX:-UsePerfData", "-jar", "/opt/pod-sandbox.jar"]

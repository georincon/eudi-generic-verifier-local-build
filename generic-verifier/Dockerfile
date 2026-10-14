# syntax=docker/dockerfile:1
# Verificador academico. Contexto de construccion: generic-verifier/
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# primero lo que cambia poco (distribucion de Gradle y definicion de modulos): mejora la cache de capas
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY verifier-protocol/build.gradle.kts verifier-protocol/
COPY verifier-trust/build.gradle.kts verifier-trust/
COPY verifier-catalog/build.gradle.kts verifier-catalog/
COPY verifier-persistence/build.gradle.kts verifier-persistence/
COPY verifier-api/build.gradle.kts verifier-api/
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY . .
RUN ./gradlew --no-daemon :verifier-api:bootJar -x test

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/verifier-api/build/libs/verifier-api-*.jar /app/verifier.jar
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "-jar", "/app/verifier.jar"]

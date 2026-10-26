# Base images are pinned by digest for reproducible builds. Update deliberately: docker buildx imagetools inspect <image>
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build
COPY pom.xml .
# maven.config + settings.xml: resolve every dependency from Maven Central only.
COPY .mvn/maven.config .mvn/settings.xml .mvn/
# The Maven repository is a BuildKit cache: rebuilds reuse downloaded dependencies instead of fetching them again.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests run outside the image build (they need Docker for Testcontainers): ./mvnw verify
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
RUN addgroup -S flowforge && adduser -S flowforge -G flowforge
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER flowforge
EXPOSE 8080
# Heap follows the container memory limit; an out-of-memory JVM exits so the container restarts instead of limping on.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]

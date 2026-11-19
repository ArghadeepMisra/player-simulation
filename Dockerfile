# One file for all three apps: docker build --build-arg MODULE=telemetry-detector .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE
# The cache mount keeps downloaded dependencies between builds, so only the first build is slow.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
RUN useradd --system --uid 1001 app && mkdir -p /tmp/kafka-streams /app/reports /data \
    && chown -R app /tmp/kafka-streams /app/reports /data
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

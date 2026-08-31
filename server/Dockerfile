FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY shared ./shared
COPY server ./server
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends wget
RUN groupadd --system ledger && useradd --system --gid ledger --home-dir /app ledger && mkdir -p /data/attachments /app && chown -R ledger:ledger /data /app
WORKDIR /app
COPY --from=build --chown=ledger:ledger /workspace/server/build/install/server/ ./
USER ledger
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]

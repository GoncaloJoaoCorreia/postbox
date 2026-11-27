FROM node:22-bookworm-slim AS spa
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN npm ci --prefix frontend --no-audit --no-fund
COPY frontend/ ./frontend/
RUN npm run build --prefix frontend

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY . .
COPY --from=spa /app/frontend/dist ./frontend/dist
RUN ./gradlew --no-daemon --console=plain -x test :backend:shadowJar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system postbox && useradd --system --gid postbox --no-create-home --shell /usr/sbin/nologin postbox
WORKDIR /app
COPY --from=build /app/backend/build/libs/postbox.jar /app/postbox.jar
RUN mkdir -p /data && chown postbox:postbox /data
USER postbox
ENV PORT=8080 POSTBOX_ENV=production DATA_DIR=/data SQLITE_PATH=/data/postbox.db
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/healthz" > /dev/null || exit 1
CMD ["java", "-jar", "/app/postbox.jar"]

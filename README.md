# postbox

Monorepo scaffold: a Kotlin/Ktor backend with room for a frontend and Docker packaging.

## Layout

- `backend/` — Kotlin 2.x / Ktor 3.x (Netty) server, Gradle module `:backend`
- `frontend/` — placeholder for the SPA (scaffolded in a later issue)
- `docker/` — placeholder for Dockerfile / docker-compose (later issue)

## Run the server

```sh
./gradlew run
curl http://localhost:8080/healthz   # -> {"status":"ok"}
```

Configuration is HOCON (`backend/src/main/resources/application.conf`) with environment
variable overrides: `PORT` (default `8080`), `JWT_SECRET`, `SQLITE_PATH` (default
`data/postbox.db`), `DATA_DIR` (default `data`). For example:

```sh
PORT=18080 ./gradlew run
curl http://localhost:18080/healthz  # -> {"status":"ok"}
```

## Build & test

```sh
./gradlew check           # compiles and runs tests (smoke test hits GET /healthz via testApplication)
./gradlew :backend:test    # backend tests only
```

Logging is structured JSON on the console (Logback + LogstashEncoder,
`backend/src/main/resources/logback.xml`).

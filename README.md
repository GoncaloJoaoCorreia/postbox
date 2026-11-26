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

## API v1

Routes live under `/api/v1`. Except `GET /api/v1/setup/status`, `POST /api/v1/setup` and
`POST /api/v1/auth/login`, every route requires an `Authorization: Bearer <token>` header —
a HS256 JWT signed with `JWT_SECRET` (no cookies, no server-side sessions). Passwords are
hashed with Argon2id.

- `GET /api/v1/setup/status` → `{"setupPending": true|false}` — true while the database has no users
- `POST /api/v1/setup` with `{"email", "password"}` → creates the first ADMIN account; `409` once any account exists
- `POST /api/v1/auth/login` with `{"email", "password"}` → `{"token": "..."}`; `401 {"error":"invalid-credentials"}` for unknown email, wrong password and deactivated accounts alike
- `PATCH /api/v1/me` with `{"newPassword"}` → changes the caller's own password
- `POST /api/v1/admin/users` with `{"email", "password", "displayName"?, "role"?}` → admin-only account creation (`403` for non-admin tokens)
- `POST /api/v1/admin/users/{id}/deactivate` and `.../reactivate` → admin-only activation toggle
- `POST /api/v1/admin/users/{id}/reset-password` with `{"newPassword"}` → admin-only password reset

## Build & test

```sh
./gradlew check           # compiles and runs tests (smoke test hits GET /healthz via testApplication)
./gradlew :backend:test    # backend tests only
```

Logging is structured JSON on the console (Logback + LogstashEncoder,
`backend/src/main/resources/logback.xml`).

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

Routes live under `/api/v1`. The OpenAPI 3 contract is checked in at `docs/openapi.yaml` and
served by a running instance at `GET /docs/openapi.yaml`. Except `GET /api/v1/setup/status`,
`POST /api/v1/setup` and `POST /api/v1/auth/login`, every route requires an
`Authorization: Bearer <token>` header — a HS256 JWT signed with `JWT_SECRET` (no cookies, no
server-side sessions). Passwords are hashed with Argon2id.

Every error response uses one envelope: `{"error": {"code": "...", "message": "..."}}` —
including `401` for missing/invalid tokens and the temporary `501 not_implemented` on the two
deferred parcel endpoints.

- `GET /api/v1/setup/status` → `{"setupPending": true|false}` — true while the database has no users
- `POST /api/v1/setup` with `{"email", "password"}` → creates the first ADMIN account; `409` once any account exists
- `POST /api/v1/auth/login` with `{"email", "password"}` → `{"token": "..."}`; `401 invalid-credentials` for unknown email, wrong password and deactivated accounts alike
- `GET /api/v1/me` → the caller's account; `PATCH /api/v1/me` with `{"newPassword"}` → changes the caller's own password (`204`)
- Admin-only (`403 admin-required` otherwise): `GET /api/v1/admin/users` → all accounts; `POST /api/v1/admin/users` with `{"email", "password", "displayName"?, "role"?}` → creates an account (`201`); `PATCH /api/v1/admin/users/{id}` with `{"isActive"?, "newPassword"?}` → deactivate/reactivate and/or reset the password → the updated account (`404` for unknown ids)
- `GET /api/v1/parcels` → own parcels, each with its current `status` and `latestEvent`, newest events first (cached data only)
- `POST /api/v1/parcels` with `{"trackingNumber", "displayName"?, "carrier"?, "metadata"?}` → `201` the created parcel (status `UNKNOWN` until first refresh); the tracking number is trimmed, length-capped and charset-checked
- `GET /api/v1/parcels/{id}`; `PATCH /api/v1/parcels/{id}` with `{"displayName"?, "archived"?, "carrier"?, "metadata"?}` (rename, archive, change carrier); `DELETE /api/v1/parcels/{id}` → `204`. Other users' parcel ids return `404` (never `403`) so ids cannot be enumerated
- `GET /api/v1/parcels/{id}/events` → full normalized timeline, newest first
- `POST /api/v1/parcels/detect` with `{"trackingNumber", "carrier"?, "metadata"?}` → `501 not_implemented` until the detection engine lands
- `POST /api/v1/parcels/{id}/refresh` → `501 not_implemented` until the refresh service lands

## Build & test

```sh
./gradlew check           # compiles and runs tests (smoke test hits GET /healthz via testApplication)
./gradlew :backend:test    # backend tests only
```

Logging is structured JSON on the console (Logback + LogstashEncoder,
`backend/src/main/resources/logback.xml`).

# postbox

Monorepo scaffold: a Kotlin/Ktor backend with room for a frontend and Docker packaging.

## Layout

- `backend/` — Kotlin 2.x / Ktor 3.x (Netty) server, Gradle module `:backend`
- `frontend/` — Vite + React + TypeScript SPA (see `frontend/README.md`)
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

## Frontend

```sh
cd frontend && npm install
npm run dev     # Vite dev server, proxies /api to the backend on http://localhost:8080
npm run build   # tsc -b && vite build -> frontend/dist
```

In production there is no separate web container: `:backend:processResources` copies
`frontend/dist` onto the classpath as `web/` and the backend serves it at `/` with SPA
history-mode fallback to `index.html`, so `npm run build` followed by `./gradlew run`
serves the SPA from Ktor itself.

## API v1

Routes live under `/api/v1`. The OpenAPI 3 contract is checked in at `docs/openapi.yaml` and
served by a running instance at `GET /docs/openapi.yaml`. Except `GET /api/v1/setup/status`,
`POST /api/v1/setup` and `POST /api/v1/auth/login`, every route requires an
`Authorization: Bearer <token>` header — a HS256 JWT signed with `JWT_SECRET` (no cookies, no
server-side sessions). Passwords are hashed with Argon2id.

Every error response uses one envelope: `{"error": {"code": "...", "message": "..."}}` —
including `401` for missing/invalid tokens.

- `GET /api/v1/setup/status` → `{"setupPending": true|false}` — true while the database has no users
- `POST /api/v1/setup` with `{"email", "password"}` → creates the first ADMIN account; `409` once any account exists
- `POST /api/v1/auth/login` with `{"email", "password"}` → `{"token": "..."}`; `401 invalid-credentials` for unknown email, wrong password and deactivated accounts alike
- `GET /api/v1/me` → the caller's account; `PATCH /api/v1/me` with `{"newPassword"}` → changes the caller's own password (`204`)
- Admin-only (`403 admin-required` otherwise): `GET /api/v1/admin/users` → all accounts; `POST /api/v1/admin/users` with `{"email", "password", "displayName"?, "role"?}` → creates an account (`201`); `PATCH /api/v1/admin/users/{id}` with `{"isActive"?, "newPassword"?}` → deactivate/reactivate and/or reset the password → the updated account (`404` for unknown ids)
- `GET /api/v1/parcels` → own parcels, each with its current `status`, `detectionMethod` and `latestEvent`, newest events first. On-demand refresh: parcels whose `lastCheckedAt` is older than the TTL (default 30 min, `POSTBOX_REFRESH_STALE_AFTER_MINUTES`) are refreshed from their carrier before the response is returned; fresh parcels serve cached data with no carrier traffic. DELIVERED parcels skip refresh after a grace window (default 3 days, `POSTBOX_REFRESH_DELIVERED_GRACE_DAYS`); in-flight carrier probes are capped (`POSTBOX_REFRESH_MAX_CONCURRENT_PROBES`, default 4) with per-carrier rate limiters respected and each probe bounded by the carrier's request timeout; a carrier outage never fails the list — cached data is served and the parcel carries a soft `lastCheckError` (`carrier unreachable, data from ...`)
- `POST /api/v1/parcels` with `{"trackingNumber", "displayName"?, "carrier"?, "metadata"?}` → `201` the created parcel (status `UNKNOWN` until first refresh); the tracking number is trimmed, length-capped and charset-checked. Without `carrier`, the detection engine resolves it (format rules → ranked candidates → parallel probe, first hit wins) and stores the outcome on the parcel (`carrier` + `detectionMethod`); an explicit `carrier` is a manual override (`detectionMethod: override`)
- `GET /api/v1/parcels/{id}`; `PATCH /api/v1/parcels/{id}` with `{"displayName"?, "archived"?, "carrier"?, "metadata"?}` (rename, archive, change carrier — a carrier change records `detectionMethod: override`); `DELETE /api/v1/parcels/{id}` → `204`. Other users' parcel ids return `404` (never `403`) so ids cannot be enumerated
- `GET /api/v1/parcels/{id}/events` → full normalized timeline, newest first
- `POST /api/v1/parcels/detect` with `{"trackingNumber", "carrier"?, "metadata"?}` → `200` `{"trackingNumber", "carrier", "method", "candidates"}` — `method` is `rule` (top candidate kept provisionally, no carrier knows the number yet), `probe` (a carrier returned real tracking data) or `override` (explicit `carrier`, never probed); `candidates` is the ranked format-rule list
- `POST /api/v1/parcels/{id}/refresh` → `200` the refreshed parcel, forcing a refresh regardless of TTL: new events inserted (deduplicated by the idempotency index), status recalculated, `lastCheckedAt` updated, `lastCheckError` cleared; on carrier outage the cached parcel comes back with `lastCheckError` set

## Build & test

```sh
./gradlew check           # compiles and runs tests (smoke test hits GET /healthz via testApplication)
./gradlew :backend:test    # backend tests only
```

Logging is structured JSON on the console (Logback + LogstashEncoder,
`backend/src/main/resources/logback.xml`).

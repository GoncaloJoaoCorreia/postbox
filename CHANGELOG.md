# Changelog

## [Unreleased]

### Added

- `frontend/`: parcel list screen at `/parcels` — parcels grouped by status (in transit / delivered / exception, plus an archived section) with carrier badge, latest event description and relative time, searchable by name or tracking number; the list rides on the server's on-demand refresh, so statuses refresh when the app opens
- `frontend/`: add-parcel flow — enter a tracking number (plus optional name), probe `POST /api/v1/parcels/detect` with a visible probing state, review the resolved carrier with a manual override select (unresolved numbers save with the top candidate, marked pending confirmation), then save via `POST /api/v1/parcels`
- `frontend/`: on existing parcels — rename, archive/unarchive, manual carrier override via `PATCH /api/v1/parcels/{id}`, and delete with an explicit confirmation via `DELETE /api/v1/parcels/{id}`
- `frontend/`: inline prompt for carrier-required metadata when the API reports it (`missingMetadata` on the list, `409 missing-carrier-metadata` with `error.fields` on refresh) — validates against `validationRegex`, saves via `PATCH metadata`, then re-refreshes the parcel
- Carrier-required parcel metadata loop: when the resolved carrier's adapter throws `NeedsMetadata`, `POST /api/v1/parcels/{id}/refresh` answers `409` with `error.code: missing-carrier-metadata` and the structured `error.fields` (`name`/`label`/`hint`/`validationRegex`) so the client can prompt for exactly the missing fields, and `GET /api/v1/parcels` serves cached data with the same list as the parcel's `missingMetadata`; adding the fields via `PATCH /api/v1/parcels/{id}` (`metadata`) makes the next refresh succeed. During multi-carrier detection probing a `NeedsMetadata` answer only eliminates a candidate — the user is asked once a carrier is actually resolved
- On-demand refresh service: `GET /api/v1/parcels` refreshes parcels whose `last_checked_at` is older than a configurable TTL (default 30 minutes, `POSTBOX_REFRESH_STALE_AFTER_MINUTES`) before the response is returned; fresh parcels serve cached data with no carrier traffic, so repeated app opens within the TTL trigger no carrier calls
- `POST /api/v1/parcels/{id}/refresh` forces a refresh regardless of TTL and returns `200` with the refreshed parcel (was `501 not_implemented`)
- Refreshed parcels get new events inserted (deduplicated by the `uq_parcel_events_idempotency` unique index), status recalculated from the carrier result, and `last_checked_at` updated
- In-flight carrier probes capped across a list refresh (`POSTBOX_REFRESH_MAX_CONCURRENT_PROBES`, default 4) while each carrier's own rate limiter stays respected; every probe is bounded by the carrier's configured request timeout, so one hanging carrier delays nothing beyond its own timeout
- Carrier outages never block the list: cached data stays and the parcel is marked with a soft `lastCheckError` (`carrier unreachable, data from ...`) — new `parcels.last_check_error` column (migration V3), exposed as `lastCheckError` on parcel responses and cleared on the next successful refresh
- DELIVERED parcels skip refresh once a configurable grace window (default 3 days, `POSTBOX_REFRESH_DELIVERED_GRACE_DAYS`) has passed since their last check
- Tracking number detection engine: per-carrier format rules produce a ranked candidate list (v1: DHL, CTT, Paack, EcoScooting), registered candidate adapters are probed in parallel (paced by the per-carrier rate limiters) and the first carrier returning real tracking data wins; a manual carrier override always wins and is never probed; when no carrier knows the number yet the top-ranked candidate is kept provisionally (`rule`) for later re-probes
- `POST /api/v1/parcels/detect` answers `200` with the resolved carrier, the method (`rule` | `probe` | `override`) and the ranked candidates
- Parcels store the detection outcome in `detection_method` (migration V2) so it can be corrected later; parcel responses include `detectionMethod`
- `frontend/`: Vite + React 19 + TypeScript (strict) SPA scaffold with ESLint + Prettier, a router with the login/register, parcel list and parcel detail areas (unauthenticated users redirect to `/login`), a typed API client generated from `docs/openapi.yaml` via `openapi-typescript` with bearer-token injection from localStorage and the `{"error": {"code", "message"}}` envelope mapped to a typed `ApiError`, TanStack Query + auth context providers, and Vitest + Testing Library + MSW tests
- `frontend/`: login screen with inline email/password validation and non-enumerating sign-in errors, plus a first-run setup wizard at `/setup` that the router selects while `GET /api/v1/setup/status` reports `setupPending` (creates the first admin via `POST /api/v1/setup`, then lands on `/login`, and is unreachable once an account exists); any `401` from the API drops the stored token and returns the SPA to `/login`
- Backend serves the SPA production build: `:backend:processResources` copies `frontend/dist` onto the classpath as `web/` and Ktor serves it at `/` with SPA history-mode fallback to `index.html` (`/api`, `/docs`, `/healthz` keep their JSON responses and 404s)
- REST API v1 surface under `/api/v1`: parcel CRUD and events with per-user scoping (other users' parcel ids return `404`, never `403`), admin account listing and `PATCH /api/v1/admin/users/{id}`, `GET /api/v1/me`, and the wired `POST /api/v1/parcels/{id}/refresh` route answering `501 not_implemented` until its service lands
- OpenAPI 3 contract at `docs/openapi.yaml`, served by a running instance at `GET /docs/openapi.yaml`
- Uniform error envelope `{"error": {"code", "message"}}` on all error responses, including 401 for missing/invalid bearer tokens

### Changed

- `GET /api/v1/parcels` now refreshes stale parcels on open instead of serving cached data only
- `POST /api/v1/parcels/{id}/refresh` returns `200` with the refreshed parcel instead of `501 not_implemented`
- `POST /api/v1/parcels` without an explicit `carrier` runs detection and stores the outcome (`carrier` + `detectionMethod`) on the new parcel; an explicit `carrier` is stored as `override`
- `PATCH /api/v1/parcels/{id}` with a `carrier` records `detectionMethod: override`
- Admin user management consolidated into `PATCH /api/v1/admin/users/{id}` (deactivate/reactivate, reset password); the `POST /api/v1/admin/users/{id}/deactivate|reactivate|reset-password` sub-routes are replaced

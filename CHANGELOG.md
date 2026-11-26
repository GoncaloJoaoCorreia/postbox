# Changelog

## [Unreleased]

### Added

- Tracking number detection engine: per-carrier format rules produce a ranked candidate list (v1: DHL, CTT, Paack, EcoScooting), registered candidate adapters are probed in parallel (paced by the per-carrier rate limiters) and the first carrier returning real tracking data wins; a manual carrier override always wins and is never probed; when no carrier knows the number yet the top-ranked candidate is kept provisionally (`rule`) for later re-probes
- `POST /api/v1/parcels/detect` answers `200` with the resolved carrier, the method (`rule` | `probe` | `override`) and the ranked candidates
- Parcels store the detection outcome in `detection_method` (migration V2) so it can be corrected later; parcel responses include `detectionMethod`
- `frontend/`: Vite + React 19 + TypeScript (strict) SPA scaffold with ESLint + Prettier, a router with the login/register, parcel list and parcel detail areas (unauthenticated users redirect to `/login`), a typed API client generated from `docs/openapi.yaml` via `openapi-typescript` with bearer-token injection from localStorage and the `{"error": {"code", "message"}}` envelope mapped to a typed `ApiError`, TanStack Query + auth context providers, and Vitest + Testing Library + MSW tests
- Backend serves the SPA production build: `:backend:processResources` copies `frontend/dist` onto the classpath as `web/` and Ktor serves it at `/` with SPA history-mode fallback to `index.html` (`/api`, `/docs`, `/healthz` keep their JSON responses and 404s)
- REST API v1 surface under `/api/v1`: parcel CRUD and events with per-user scoping (other users' parcel ids return `404`, never `403`), admin account listing and `PATCH /api/v1/admin/users/{id}`, `GET /api/v1/me`, and the wired `POST /api/v1/parcels/{id}/refresh` route answering `501 not_implemented` until its service lands
- OpenAPI 3 contract at `docs/openapi.yaml`, served by a running instance at `GET /docs/openapi.yaml`
- Uniform error envelope `{"error": {"code", "message"}}` on all error responses, including 401 for missing/invalid bearer tokens

### Changed

- `POST /api/v1/parcels` without an explicit `carrier` runs detection and stores the outcome (`carrier` + `detectionMethod`) on the new parcel; an explicit `carrier` is stored as `override`
- `PATCH /api/v1/parcels/{id}` with a `carrier` records `detectionMethod: override`
- Admin user management consolidated into `PATCH /api/v1/admin/users/{id}` (deactivate/reactivate, reset password); the `POST /api/v1/admin/users/{id}/deactivate|reactivate|reset-password` sub-routes are replaced

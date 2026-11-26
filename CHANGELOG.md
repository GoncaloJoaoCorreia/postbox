# Changelog

## [Unreleased]

### Added

- REST API v1 surface under `/api/v1`: parcel CRUD and events with per-user scoping (other users' parcel ids return `404`, never `403`), admin account listing and `PATCH /api/v1/admin/users/{id}`, `GET /api/v1/me`, and the wired `POST /api/v1/parcels/detect` / `POST /api/v1/parcels/{id}/refresh` routes answering `501 not_implemented` until their services land
- OpenAPI 3 contract at `docs/openapi.yaml`, served by a running instance at `GET /docs/openapi.yaml`
- Uniform error envelope `{"error": {"code", "message"}}` on all error responses, including 401 for missing/invalid bearer tokens

### Changed

- Admin user management consolidated into `PATCH /api/v1/admin/users/{id}` (deactivate/reactivate, reset password); the `POST /api/v1/admin/users/{id}/deactivate|reactivate|reset-password` sub-routes are replaced

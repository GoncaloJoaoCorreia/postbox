-- V6: device sessions backing refresh-token auth (mobile clients). One row per
-- login session. The refresh token itself is never stored -- only its SHA-256
-- hash in `token_hash`, plus the previously rotated hash in
-- `previous_token_hash` so replaying a rotated token is detectable and revokes
-- the session. `expires_at` carries the sliding TTL (default 90 days, extended
-- on every successful refresh); `revoked_at` is set on explicit revocation
-- (device list, password change, admin deactivation) or on token reuse.
-- All timestamps are ISO-8601 UTC text.

CREATE TABLE device_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    previous_token_hash TEXT NULL UNIQUE,
    device_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_used_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT NULL
);

CREATE INDEX idx_device_sessions_user_id ON device_sessions(user_id);

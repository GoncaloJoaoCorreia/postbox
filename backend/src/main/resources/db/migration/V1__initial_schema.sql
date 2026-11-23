-- V1: initial schema. All ids are UUID text; all timestamps are ISO-8601 UTC text.

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE invitations (
    id TEXT PRIMARY KEY,
    token TEXT NOT NULL UNIQUE,
    created_by TEXT NULL REFERENCES users(id),
    expires_at TEXT NOT NULL,
    used_at TEXT NULL
);

CREATE TABLE parcels (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    tracking_number TEXT NOT NULL,
    carrier TEXT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    carrier_metadata TEXT NULL,
    last_checked_at TEXT NULL,
    created_at TEXT NOT NULL,
    archived_at TEXT NULL
);

CREATE INDEX idx_parcels_user_id ON parcels(user_id);

CREATE TABLE parcel_events (
    id TEXT PRIMARY KEY,
    parcel_id TEXT NOT NULL REFERENCES parcels(id),
    occurred_at TEXT NOT NULL,
    status TEXT NOT NULL,
    description TEXT NOT NULL,
    location TEXT NULL,
    raw TEXT NOT NULL
);

CREATE INDEX idx_parcel_events_parcel_id ON parcel_events(parcel_id);

-- Unique event index keeps future carrier refreshes idempotent (no duplicate events).
CREATE UNIQUE INDEX uq_parcel_events_idempotency
    ON parcel_events(parcel_id, occurred_at, status, description);

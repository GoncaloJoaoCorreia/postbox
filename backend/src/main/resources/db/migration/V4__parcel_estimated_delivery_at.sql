-- V4: remember the ETA the carrier provided on the last successful refresh, so the
-- API can surface it as an estimated delivery time. NULL when the carrier does not
-- provide one or the parcel was never successfully checked.

ALTER TABLE parcels ADD COLUMN estimated_delivery_at TEXT NULL;

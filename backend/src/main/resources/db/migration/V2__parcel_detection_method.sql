-- V2: record how the carrier of a parcel was resolved (rule | probe | override), so a
-- manual correction stays distinguishable from auto-detection. NULL on rows that
-- predate detection.

ALTER TABLE parcels ADD COLUMN detection_method TEXT NULL
    CHECK (detection_method IN ('rule', 'probe', 'override'));

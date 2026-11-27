-- V5: structured soft-failure state on a parcel. `last_check_error_code` is the
-- machine-readable code of the last failed check ("carrier_unreachable" or
-- "rate_limited"); `rate_limited_until` carries the carrier's Retry-After hint as
-- an ISO-8601 UTC timestamp for rate-limited checks. Both NULL when the last check
-- succeeded or the parcel was never checked.

ALTER TABLE parcels ADD COLUMN last_check_error_code TEXT NULL;
ALTER TABLE parcels ADD COLUMN rate_limited_until TEXT NULL;

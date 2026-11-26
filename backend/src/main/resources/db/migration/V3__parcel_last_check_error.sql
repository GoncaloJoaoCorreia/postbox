-- V3: remember the last soft refresh failure on a parcel (e.g. "carrier
-- unreachable, data from ..."), so the API can surface it as an indicator.
-- NULL when the last check succeeded or the parcel was never checked.

ALTER TABLE parcels ADD COLUMN last_check_error TEXT NULL;

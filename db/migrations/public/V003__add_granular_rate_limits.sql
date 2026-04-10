-- V003: Add granular rate limit columns to tenants table
-- Separate write and read rate limits for fine-grained control per endpoint category.
-- Write limit (POST/PUT/PATCH/DELETE) defaults to 30 RPM.
-- Read limit (GET/HEAD/OPTIONS) defaults to 200 RPM.
-- The existing rate_limit_rpm column is kept as a global fallback reference.

ALTER TABLE tenants
    ADD COLUMN rate_limit_write_rpm INT NOT NULL DEFAULT 30,
    ADD COLUMN rate_limit_read_rpm  INT NOT NULL DEFAULT 200;

COMMENT ON COLUMN tenants.rate_limit_write_rpm IS 'Max write requests (POST/PUT/PATCH/DELETE) per minute per API key';
COMMENT ON COLUMN tenants.rate_limit_read_rpm  IS 'Max read requests (GET/HEAD/OPTIONS) per minute per API key';

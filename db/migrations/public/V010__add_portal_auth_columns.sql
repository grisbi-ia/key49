-- V010: Add portal authentication columns (email + password) to tenants
-- Supports dual login: API key (existing) + email/password (new)

ALTER TABLE tenants
    ADD COLUMN email VARCHAR(255),
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN portal_password_hash VARCHAR(255);

-- Unique constraint on email (only non-null values)
CREATE UNIQUE INDEX uq_tenants_email ON tenants (email) WHERE email IS NOT NULL;

COMMENT ON COLUMN tenants.email IS 'Portal login email for password-based authentication';
COMMENT ON COLUMN tenants.email_verified IS 'Whether the email has been verified';
COMMENT ON COLUMN tenants.portal_password_hash IS 'BCrypt hash (cost 12) of portal password';

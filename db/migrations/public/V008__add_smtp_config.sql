-- V008: Add per-tenant SMTP configuration columns
-- Allows tenants to send emails via their own SMTP server instead of Key49 shared SMTP.
-- Password is encrypted with AES-256-GCM using the same master key as certificate passwords.

ALTER TABLE tenants
    ADD COLUMN smtp_host         VARCHAR(255),
    ADD COLUMN smtp_port         INTEGER,
    ADD COLUMN smtp_user         VARCHAR(255),
    ADD COLUMN smtp_password_enc BYTEA,
    ADD COLUMN smtp_from         VARCHAR(255),
    ADD COLUMN smtp_enabled      BOOLEAN NOT NULL DEFAULT false;

-- Constraint: if smtp_enabled, host and port are required
ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_smtp_config
    CHECK (
        smtp_enabled = false
        OR (smtp_host IS NOT NULL AND smtp_port IS NOT NULL)
    );

-- Constraint: smtp_port must be in valid range
ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_smtp_port
    CHECK (smtp_port IS NULL OR (smtp_port >= 1 AND smtp_port <= 65535));

COMMENT ON COLUMN tenants.smtp_host IS 'SMTP server hostname for tenant-specific email sending';
COMMENT ON COLUMN tenants.smtp_port IS 'SMTP server port (25, 465, 587)';
COMMENT ON COLUMN tenants.smtp_user IS 'SMTP authentication username';
COMMENT ON COLUMN tenants.smtp_password_enc IS 'SMTP password encrypted with AES-256-GCM (same master key as certificates)';
COMMENT ON COLUMN tenants.smtp_from IS 'Email From address for tenant-specific SMTP';
COMMENT ON COLUMN tenants.smtp_enabled IS 'Whether to use tenant SMTP instead of Key49 shared SMTP';

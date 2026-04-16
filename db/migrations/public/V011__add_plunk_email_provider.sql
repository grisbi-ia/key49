-- V011: Add email provider selection and Plunk API key to tenants
-- Allows tenants to choose between SMTP (default) and Plunk as the email
-- delivery channel for electronic document notifications.
-- The Plunk API key is encrypted with AES-256-GCM using the same master key
-- as certificate passwords and SMTP passwords.

ALTER TABLE tenants
    ADD COLUMN email_provider    VARCHAR(20) NOT NULL DEFAULT 'smtp',
    ADD COLUMN plunk_api_key_enc BYTEA;

-- Constraint: email_provider must be one of the supported values
ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_email_provider
    CHECK (email_provider IN ('smtp', 'plunk'));

-- Constraint: if email_provider = 'plunk', api key must be present
ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_plunk_api_key
    CHECK (
        email_provider <> 'plunk'
        OR plunk_api_key_enc IS NOT NULL
    );

COMMENT ON COLUMN tenants.email_provider    IS 'Email delivery channel for document notifications: smtp | plunk';
COMMENT ON COLUMN tenants.plunk_api_key_enc IS 'Plunk secret API key (sk_*) encrypted with AES-256-GCM (same master key as certificates)';

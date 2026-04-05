-- V002: Create api_keys table in public schema
-- Stores API keys for tenant authentication

CREATE TABLE api_keys (
    api_key_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    key_prefix  VARCHAR(8) NOT NULL,
    key_hash    VARCHAR(128) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    permissions VARCHAR(500) NOT NULL DEFAULT '*',
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash),
    CONSTRAINT chk_api_keys_status CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_prefix ON api_keys(key_prefix);

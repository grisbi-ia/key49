-- V001: Create tenants table in public schema
-- Stores tenant registration and configuration

CREATE TABLE tenants (
    tenant_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ruc                     VARCHAR(13) NOT NULL,
    legal_name              VARCHAR(300) NOT NULL,
    trade_name              VARCHAR(300),
    main_address            VARCHAR(300) NOT NULL,
    required_accounting     BOOLEAN NOT NULL DEFAULT false,
    special_taxpayer        VARCHAR(20),
    micro_enterprise_regime BOOLEAN NOT NULL DEFAULT false,
    withholding_agent       VARCHAR(10),
    environment             VARCHAR(10) NOT NULL DEFAULT 'test',
    emission_type           SMALLINT NOT NULL DEFAULT 1,
    logo_url                VARCHAR(500),

    -- Digital certificate (encrypted AES-256-GCM)
    certificate_p12             BYTEA,
    certificate_password_enc    BYTEA,
    certificate_subject         VARCHAR(500),
    certificate_expiration      TIMESTAMP WITH TIME ZONE,
    certificate_serial          VARCHAR(100),

    -- Configuration
    webhook_url         VARCHAR(500),
    webhook_secret      VARCHAR(100),
    rate_limit_rpm      INT NOT NULL DEFAULT 100,
    email_sender_name   VARCHAR(200),
    reply_email         VARCHAR(200),

    -- Tenant PostgreSQL schema
    schema_name         VARCHAR(63) NOT NULL,

    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT uq_tenants_ruc UNIQUE (ruc),
    CONSTRAINT uq_tenants_schema UNIQUE (schema_name),
    CONSTRAINT chk_tenants_environment CHECK (environment IN ('test', 'production')),
    CONSTRAINT chk_tenants_status CHECK (status IN ('active', 'suspended', 'pending'))
);

CREATE INDEX idx_tenants_status ON tenants(status);

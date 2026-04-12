-- V007: Add plan/quota columns to tenants and create plan_renewals table
-- Supports SaaS commercialization: plan types, document quotas, renewal tracking.

-- ── 1. New columns on tenants ───────────────────────────────────────────────
ALTER TABLE tenants
    ADD COLUMN plan_type       VARCHAR(20) NOT NULL DEFAULT 'demo',
    ADD COLUMN document_quota  INTEGER     NOT NULL DEFAULT 25,
    ADD COLUMN documents_used  INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN plan_starts_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN plan_expires_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_plan_type
        CHECK (plan_type IN ('demo', 'starter', 'business', 'enterprise'));

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_document_quota CHECK (document_quota > 0);

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_documents_used CHECK (documents_used >= 0);

COMMENT ON COLUMN tenants.plan_type       IS 'Plan comercial: demo, starter, business, enterprise';
COMMENT ON COLUMN tenants.document_quota  IS 'Documentos permitidos en el periodo actual';
COMMENT ON COLUMN tenants.documents_used  IS 'Documentos emitidos en el periodo actual';
COMMENT ON COLUMN tenants.plan_starts_at  IS 'Inicio del periodo de facturación';
COMMENT ON COLUMN tenants.plan_expires_at IS 'Fin del periodo de facturación (30 días desde activación)';

-- ── 2. Update status CHECK to include 'failed' (used by provisioning) ───────
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS chk_tenants_status;
ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_status
        CHECK (status IN ('active', 'suspended', 'pending', 'failed'));

-- ── 3. plan_renewals table ──────────────────────────────────────────────────
CREATE TABLE plan_renewals (
    renewal_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    plan_type          VARCHAR(20) NOT NULL,
    document_quota     INTEGER NOT NULL,
    amount             NUMERIC(10,2) NOT NULL DEFAULT 0,
    payment_proof_path VARCHAR(500),
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    approved_by        VARCHAR(200),
    approved_at        TIMESTAMP WITH TIME ZONE,
    notes              TEXT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT chk_renewals_plan_type
        CHECK (plan_type IN ('demo', 'starter', 'business', 'enterprise')),
    CONSTRAINT chk_renewals_status
        CHECK (status IN ('pending', 'approved', 'rejected')),
    CONSTRAINT chk_renewals_quota CHECK (document_quota > 0),
    CONSTRAINT chk_renewals_amount CHECK (amount >= 0)
);

CREATE INDEX idx_plan_renewals_tenant ON plan_renewals(tenant_id);
CREATE INDEX idx_plan_renewals_status ON plan_renewals(status);

COMMENT ON TABLE plan_renewals IS 'Historial de renovaciones/cambios de plan por tenant';

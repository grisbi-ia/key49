-- V004: Create audit_log table in public schema
-- Centralized audit trail for sensitive operations across all tenants

CREATE TABLE audit_log (
    audit_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(tenant_id),
    actor        VARCHAR(100) NOT NULL,
    action       VARCHAR(50)  NOT NULL,
    resource     VARCHAR(50)  NOT NULL,
    resource_id  UUID,
    ip_address   VARCHAR(45),
    details      JSONB,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_tenant    ON audit_log(tenant_id);
CREATE INDEX idx_audit_log_action    ON audit_log(action);
CREATE INDEX idx_audit_log_created   ON audit_log(created_at);

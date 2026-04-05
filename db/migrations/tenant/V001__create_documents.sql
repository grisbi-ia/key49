-- V001: Create documents table in tenant schema
-- Central table for electronic documents (invoices, credit notes, etc.)
-- This script runs inside each tenant schema (SET search_path TO tenant_xxx)

CREATE TABLE documents (
    document_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Document identification
    document_type       VARCHAR(2) NOT NULL,
    establishment       VARCHAR(3) NOT NULL,
    issue_point         VARCHAR(3) NOT NULL,
    sequence_number     VARCHAR(9) NOT NULL,
    access_key          VARCHAR(49),
    authorization_number VARCHAR(49),

    -- Request origin
    request_origin      VARCHAR(10) NOT NULL DEFAULT 'JSON',

    -- Recipient data
    recipient_id_type   VARCHAR(2) NOT NULL,
    recipient_id        VARCHAR(20) NOT NULL,
    recipient_name      VARCHAR(300) NOT NULL,
    recipient_email     VARCHAR(500),
    recipient_address   VARCHAR(300),
    recipient_phone     VARCHAR(50),

    -- Amounts
    subtotal_before_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_vat_0      NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_vat_12     NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_vat_15     NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_non_taxable NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_exempt     NUMERIC(14,2) NOT NULL DEFAULT 0,
    vat_amount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    ice_amount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    tip                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    currency            VARCHAR(15) NOT NULL DEFAULT 'DOLAR',

    -- Dates
    issue_date          DATE NOT NULL,
    authorization_date  TIMESTAMP WITH TIME ZONE,
    sri_submission_date TIMESTAMP WITH TIME ZONE,

    -- Pipeline status
    status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',

    -- Processing
    retry_count         SMALLINT NOT NULL DEFAULT 0,
    max_retries         SMALLINT NOT NULL DEFAULT 6,
    next_retry_at       TIMESTAMP WITH TIME ZONE,
    last_error_code     VARCHAR(10),
    last_error_message  TEXT,
    sri_messages        JSONB,

    -- Storage (MinIO paths)
    unsigned_xml_path   VARCHAR(500),
    signed_xml_path     VARCHAR(500),
    authorized_xml_path VARCHAR(500),
    ride_path           VARCHAR(500),

    -- Original request data
    request_payload     JSONB,
    original_xml        TEXT,
    request_ip          VARCHAR(45),
    idempotency_key     VARCHAR(50),

    -- Email delivery tracking
    email_sent_at       TIMESTAMP WITH TIME ZONE,
    email_status        VARCHAR(20),
    email_error         VARCHAR(500),

    -- Void/cancellation (local only)
    voided_at           TIMESTAMP WITH TIME ZONE,
    void_reason         VARCHAR(500),

    -- Metadata
    version             INT NOT NULL DEFAULT 1,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT uq_documents_access_key UNIQUE (access_key),
    CONSTRAINT uq_documents_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_documents_number UNIQUE (document_type, establishment, issue_point, sequence_number),
    CONSTRAINT chk_documents_status CHECK (
        status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'AUTHORIZED', 'NOTIFIED', 'REJECTED', 'FAILED', 'RETRY', 'VOIDED')
    ),
    CONSTRAINT chk_documents_origin CHECK (
        (request_origin = 'JSON' AND request_payload IS NOT NULL) OR
        (request_origin = 'XML_RAW' AND original_xml IS NOT NULL)
    )
);

CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_issue_date ON documents(issue_date DESC);
CREATE INDEX idx_documents_recipient ON documents(recipient_id);
CREATE INDEX idx_documents_retry ON documents(status, next_retry_at) WHERE status = 'RETRY';
CREATE INDEX idx_documents_access_key ON documents(access_key) WHERE access_key IS NOT NULL;
CREATE INDEX idx_documents_type_date ON documents(document_type, issue_date DESC);
CREATE INDEX idx_documents_origin ON documents(request_origin);

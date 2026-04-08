package auracore.key49.queue.consumer;

/**
 * SQL DDL helpers compartidos por los tests de integración de consumers.
 */
final class TestSchemaHelper {

    private TestSchemaHelper() {
    }

    static String documentsTableSql(String schema) {
        return """
                CREATE TABLE IF NOT EXISTS %s.documents (
                    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    document_type VARCHAR(2) NOT NULL,
                    establishment VARCHAR(3) NOT NULL,
                    issue_point VARCHAR(3) NOT NULL,
                    sequence_number VARCHAR(9) NOT NULL,
                    access_key VARCHAR(49),
                    authorization_number VARCHAR(49),
                    request_origin VARCHAR(10) NOT NULL DEFAULT 'JSON',
                    recipient_id_type VARCHAR(2) NOT NULL,
                    recipient_id VARCHAR(20) NOT NULL,
                    recipient_name VARCHAR(300) NOT NULL,
                    recipient_email VARCHAR(500),
                    recipient_address VARCHAR(300),
                    recipient_phone VARCHAR(50),
                    subtotal_before_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
                    total_discount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_vat_0 NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_vat_12 NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_vat_15 NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_non_taxable NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_exempt NUMERIC(14,2) NOT NULL DEFAULT 0,
                    vat_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    ice_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    tip NUMERIC(14,2) NOT NULL DEFAULT 0,
                    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    currency VARCHAR(15) NOT NULL DEFAULT 'DOLAR',
                    issue_date DATE NOT NULL,
                    authorization_date TIMESTAMPTZ,
                    sri_submission_date TIMESTAMPTZ,
                    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                    retry_count SMALLINT NOT NULL DEFAULT 0,
                    max_retries SMALLINT NOT NULL DEFAULT 6,
                    next_retry_at TIMESTAMPTZ,
                    last_error_code VARCHAR(10),
                    last_error_message TEXT,
                    sri_messages JSONB,
                    unsigned_xml_path VARCHAR(500),
                    signed_xml_path VARCHAR(500),
                    authorized_xml_path VARCHAR(500),
                    ride_path VARCHAR(500),
                    request_payload JSONB,
                    original_xml TEXT,
                    request_ip VARCHAR(45),
                    idempotency_key VARCHAR(50),
                    email_sent_at TIMESTAMPTZ,
                    email_status VARCHAR(20),
                    email_error VARCHAR(500),
                    voided_at TIMESTAMPTZ,
                    void_reason VARCHAR(500),
                    version INT NOT NULL DEFAULT 1,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_%s_doc_number UNIQUE (document_type, establishment, issue_point, sequence_number),
                    CONSTRAINT uq_%s_doc_access_key UNIQUE (access_key),
                    CONSTRAINT uq_%s_doc_idempotency UNIQUE (idempotency_key)
                )
                """.formatted(schema, schema, schema, schema);
    }

    static String outboxTableSql(String schema) {
        return """
                CREATE TABLE IF NOT EXISTS %s.outbox (
                    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    aggregate_type VARCHAR(50) NOT NULL,
                    aggregate_id UUID NOT NULL,
                    event_type VARCHAR(50) NOT NULL,
                    payload JSONB NOT NULL,
                    published BOOLEAN NOT NULL DEFAULT false,
                    published_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema);
    }

    static String webhookDeliveriesTableSql(String schema) {
        return """
                CREATE TABLE IF NOT EXISTS %s.webhook_deliveries (
                    webhook_delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    document_id UUID NOT NULL,
                    event_type VARCHAR(50) NOT NULL,
                    url VARCHAR(500) NOT NULL,
                    request_body JSONB NOT NULL,
                    response_status SMALLINT,
                    response_body TEXT,
                    duration_ms INT,
                    attempt SMALLINT NOT NULL DEFAULT 1,
                    max_attempts SMALLINT NOT NULL DEFAULT 3,
                    next_attempt_at TIMESTAMPTZ,
                    status VARCHAR(20) NOT NULL DEFAULT 'pending',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema);
    }
}

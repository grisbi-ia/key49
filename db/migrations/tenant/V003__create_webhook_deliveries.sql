-- V003: Create webhook_deliveries table in tenant schema
-- Log of webhook deliveries to integrators

CREATE TABLE webhook_deliveries (
    webhook_delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(document_id),

    event_type      VARCHAR(50) NOT NULL,
    url             VARCHAR(500) NOT NULL,
    request_body    JSONB NOT NULL,
    response_status INT,
    response_body   TEXT,
    duration_ms     INT,

    attempt         SMALLINT NOT NULL DEFAULT 1,
    max_attempts    SMALLINT NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMP WITH TIME ZONE,

    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT chk_webhook_status CHECK (status IN ('pending', 'delivered', 'failed'))
);

CREATE INDEX idx_webhook_pending ON webhook_deliveries(status, next_attempt_at) WHERE status = 'pending';

-- V002: Create outbox table in tenant schema
-- Outbox pattern for guaranteed event delivery to RabbitMQ

CREATE TABLE outbox (
    outbox_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload         JSONB NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT false,
    published_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published = false;

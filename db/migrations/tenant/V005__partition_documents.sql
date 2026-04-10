-- V005: Convert documents table to range-partitioned by issue_date
--
-- Purpose:
--   Partition the documents table by monthly ranges of issue_date.
--   With ~1000 documents/day per large tenant, partitioning improves:
--   - Query performance: date-range queries benefit from partition pruning
--   - Maintenance: VACUUM/ANALYZE operates on individual partitions
--   - Archival: old partitions can be detached/archived independently
--
-- Why issue_date instead of created_at?
--   All list queries filter by issue_date (e.g., WHERE issue_date >= :from AND issue_date <= :to).
--   Partition pruning only works when the partition key is in the WHERE clause.
--   Since issue_date is validated to be today (Rule 15), issue_date ≈ created_at.
--
-- Impact on constraints:
--   - PK becomes (document_id, issue_date) — PostgreSQL requires partition key in PK
--   - UNIQUE constraints include issue_date — practically safe because:
--     * access_key: 49-digit with embedded date, globally unique by construction
--     * idempotency_key: per-request UUID, never reused across dates
--     * document_number: sequence_number is sequential within the same issue_point
--   - FK from webhook_deliveries is dropped — referential integrity enforced at application level
--
-- JPA compatibility:
--   - No changes needed to Document.java entity (@Id on document_id still works)
--   - Hibernate queries route correctly through PostgreSQL partition router
--   - UUID uniqueness guarantees single-row results for PK lookups
--
-- Prerequisites:
--   - Run inside each tenant schema: SET search_path TO tenant_xxx;
--   - Back up the schema before running
--   - Run during a maintenance window (table is rewritten)
--
-- Estimated time: < 1 minute per million rows
--
-- To test in a scratch schema:
--   CREATE SCHEMA test_v005;
--   SET search_path TO test_v005;
--   \i db/migrations/tenant/V001__create_documents.sql
--   \i db/migrations/tenant/V003__create_webhook_deliveries.sql
--   \i db/migrations/tenant/V005__partition_documents.sql
--   EXPLAIN SELECT * FROM documents WHERE issue_date BETWEEN '2026-03-01' AND '2026-03-31';
--   DROP SCHEMA test_v005 CASCADE;

BEGIN;

-- ── 1. Remove FK from webhook_deliveries ────────────────────────────────────
-- Cannot reference a partitioned table's compound PK with a single-column FK.
-- Referential integrity is enforced at the application level (all webhook
-- deliveries are created for existing, already-authorized documents).
ALTER TABLE webhook_deliveries
    DROP CONSTRAINT IF EXISTS webhook_deliveries_document_id_fkey;

-- ── 2. Rename existing table ────────────────────────────────────────────────
ALTER TABLE documents RENAME TO documents_old;

-- ── 3. Free constraint names (they belong to the renamed table) ─────────────
ALTER TABLE documents_old DROP CONSTRAINT IF EXISTS documents_pkey;
ALTER TABLE documents_old DROP CONSTRAINT IF EXISTS uq_documents_access_key;
ALTER TABLE documents_old DROP CONSTRAINT IF EXISTS uq_documents_idempotency;
ALTER TABLE documents_old DROP CONSTRAINT IF EXISTS uq_documents_number;
ALTER TABLE documents_old DROP CONSTRAINT IF EXISTS chk_documents_status;
ALTER TABLE documents_old DROP CONSTRAINT IF EXISTS chk_documents_origin;

-- ── 4. Free index names ─────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_documents_status;
DROP INDEX IF EXISTS idx_documents_issue_date;
DROP INDEX IF EXISTS idx_documents_recipient;
DROP INDEX IF EXISTS idx_documents_retry;
DROP INDEX IF EXISTS idx_documents_access_key;
DROP INDEX IF EXISTS idx_documents_type_date;
DROP INDEX IF EXISTS idx_documents_origin;

-- ── 5. Create new partitioned table ─────────────────────────────────────────
-- Same columns as V001, with compound PK and partition-compatible UNIQUE constraints.
CREATE TABLE documents (
    document_id         UUID NOT NULL DEFAULT gen_random_uuid(),

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

    -- Compound PK (partition key must be included)
    PRIMARY KEY (document_id, issue_date),

    -- UNIQUE constraints include issue_date (PostgreSQL requirement for partitioned tables)
    CONSTRAINT uq_documents_access_key UNIQUE (access_key, issue_date),
    CONSTRAINT uq_documents_idempotency UNIQUE (idempotency_key, issue_date),
    CONSTRAINT uq_documents_number UNIQUE (document_type, establishment, issue_point, sequence_number, issue_date),

    -- CHECK constraints (unchanged)
    CONSTRAINT chk_documents_status CHECK (
        status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'AUTHORIZED', 'NOTIFIED', 'REJECTED', 'FAILED', 'RETRY', 'VOIDED')
    ),
    CONSTRAINT chk_documents_origin CHECK (
        (request_origin = 'JSON' AND request_payload IS NOT NULL) OR
        (request_origin = 'XML_RAW' AND original_xml IS NOT NULL)
    )
) PARTITION BY RANGE (issue_date);

-- ── 6. Default partition (catches data outside defined monthly ranges) ──────
CREATE TABLE documents_default PARTITION OF documents DEFAULT;

-- ── 7. Monthly partitions for current year ──────────────────────────────────
-- Uses CURRENT_DATE to determine the year dynamically.
-- Data from previous years lands in documents_default; the DBA can create
-- explicit yearly/monthly partitions retroactively if needed.
DO $$
DECLARE
    yr       INT := EXTRACT(YEAR FROM CURRENT_DATE)::INT;
    m        INT;
    start_d  DATE;
    end_d    DATE;
    part_name TEXT;
BEGIN
    FOR m IN 1..12 LOOP
        start_d := make_date(yr, m, 1);
        IF m = 12 THEN
            end_d := make_date(yr + 1, 1, 1);
        ELSE
            end_d := make_date(yr, m + 1, 1);
        END IF;
        part_name := format('documents_%s_%s', yr, lpad(m::text, 2, '0'));
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF documents FOR VALUES FROM (%L) TO (%L)',
            part_name, start_d, end_d
        );
    END LOOP;
END $$;

-- ── 8. Migrate data ─────────────────────────────────────────────────────────
-- Rows route automatically to the correct partition based on issue_date.
INSERT INTO documents SELECT * FROM documents_old;

-- ── 9. Drop old table ───────────────────────────────────────────────────────
DROP TABLE documents_old;

-- ── 10. Recreate indexes on parent (inherited by each partition) ─────────────
CREATE INDEX idx_documents_status      ON documents(status);
CREATE INDEX idx_documents_issue_date  ON documents(issue_date DESC);
CREATE INDEX idx_documents_recipient   ON documents(recipient_id);
CREATE INDEX idx_documents_retry       ON documents(status, next_retry_at) WHERE status = 'RETRY';
CREATE INDEX idx_documents_access_key  ON documents(access_key) WHERE access_key IS NOT NULL;
CREATE INDEX idx_documents_type_date   ON documents(document_type, issue_date DESC);
CREATE INDEX idx_documents_origin      ON documents(request_origin);
-- New: created_at index for time-series monitoring queries
CREATE INDEX idx_documents_created_at  ON documents(created_at DESC);

-- ── 11. Table comment ───────────────────────────────────────────────────────
COMMENT ON TABLE documents IS 'Range-partitioned by issue_date (monthly). See V005.';

COMMIT;

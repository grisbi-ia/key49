-- V006: Create clone_schema() function and tenant_template schema
--
-- Purpose:
--   Enable automated tenant provisioning by cloning a template schema.
--   Instead of running V001-V006 tenant migrations for each new tenant,
--   clone_schema copies the pre-built template in a single operation.
--
-- Task: T-090 (Phase 8 - SaaS Commercialization)
--
-- Usage:
--   SELECT clone_schema('tenant_template', 'tenant_newclient');
--
-- Prerequisites:
--   - Run as database owner or superuser
--   - Public schema migrations V001-V005 must be applied first
--   - Run in the key49 database
--
-- Maintenance:
--   When adding new tenant migrations (e.g., V007), also apply them to
--   tenant_template so that new tenants get the latest schema.

BEGIN;

-- ══════════════════════════════════════════════════════════════════════════════
-- Part 1: clone_schema() PL/pgSQL function
-- ══════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION public.clone_schema(
    source_schema TEXT,
    target_schema TEXT
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    rec             RECORD;
    idx_rec         RECORD;
    partition_rec   RECORD;
    pk_cols         TEXT;
    indexdef_sql     TEXT;
BEGIN
    -- ── 1. Validate inputs ──────────────────────────────────────────────────
    IF source_schema IS NULL OR target_schema IS NULL THEN
        RAISE EXCEPTION 'Source and target schema names must not be NULL'
            USING ERRCODE = 'null_value_not_allowed';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = source_schema) THEN
        RAISE EXCEPTION 'Source schema "%" does not exist', source_schema
            USING ERRCODE = 'invalid_schema_name';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = target_schema) THEN
        RAISE EXCEPTION 'Target schema "%" already exists — aborting to prevent overwrite', target_schema
            USING ERRCODE = 'duplicate_schema';
    END IF;

    IF target_schema !~ '^[a-z][a-z0-9_]*$' OR length(target_schema) > 63 THEN
        RAISE EXCEPTION 'Invalid schema name "%": must match ^[a-z][a-z0-9_]*$ and length <= 63', target_schema
            USING ERRCODE = 'invalid_name';
    END IF;

    -- ── 2. Create target schema ─────────────────────────────────────────────
    EXECUTE format('CREATE SCHEMA %I', target_schema);

    -- ── 3. Clone sequences ──────────────────────────────────────────────────
    FOR rec IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE n.nspname = source_schema AND c.relkind = 'S'
        ORDER BY c.relname
    LOOP
        EXECUTE format('CREATE SEQUENCE %I.%I', target_schema, rec.relname);
    END LOOP;

    -- ── 4. Clone regular (non-partitioned, non-partition-child) tables ──────
    --    Uses LIKE INCLUDING ALL which copies:
    --    columns, defaults, NOT NULL, CHECK constraints, PK, UNIQUE, indexes,
    --    comments, storage parameters, generated columns.
    --    Does NOT copy: foreign keys (none exist after V005).
    FOR rec IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE n.nspname = source_schema
          AND c.relkind = 'r'          -- regular table
          AND NOT c.relispartition     -- not a partition child
        ORDER BY c.relname
    LOOP
        EXECUTE format(
            'CREATE TABLE %I.%I (LIKE %I.%I INCLUDING ALL)',
            target_schema, rec.relname, source_schema, rec.relname
        );
    END LOOP;

    -- ── 5. Clone partitioned tables ─────────────────────────────────────────
    --    Partitioned tables require special handling:
    --    - LIKE copies columns + CHECK constraints but NOT partition definition
    --    - PK/UNIQUE are index-based, must be added after partitions exist
    --    - Indexes on parent auto-propagate to partitions
    FOR rec IN
        SELECT c.relname,
               pg_get_partkeydef(c.oid) AS partkeydef
        FROM pg_class c
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE n.nspname = source_schema
          AND c.relkind = 'p'          -- partitioned table
        ORDER BY c.relname
    LOOP
        -- 5a. Create partitioned parent (columns + defaults + CHECK constraints)
        EXECUTE format(
            'CREATE TABLE %I.%I (LIKE %I.%I INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING COMMENTS INCLUDING STORAGE INCLUDING GENERATED) PARTITION BY %s',
            target_schema, rec.relname,
            source_schema, rec.relname,
            rec.partkeydef
        );

        -- 5b. Create child partitions with identical bounds
        FOR partition_rec IN
            SELECT child.relname AS part_name,
                   pg_get_expr(child.relpartbound, child.oid) AS bound_expr
            FROM pg_inherits inh
            JOIN pg_class parent ON inh.inhparent = parent.oid
            JOIN pg_class child  ON inh.inhrelid  = child.oid
            JOIN pg_namespace pn ON parent.relnamespace = pn.oid
            WHERE pn.nspname = source_schema
              AND parent.relname = rec.relname
            ORDER BY child.relname
        LOOP
            EXECUTE format(
                'CREATE TABLE %I.%I PARTITION OF %I.%I %s',
                target_schema, partition_rec.part_name,
                target_schema, rec.relname,
                partition_rec.bound_expr
            );
        END LOOP;

        -- 5c. Add PRIMARY KEY constraint (auto-propagates to all partitions)
        SELECT string_agg(a.attname, ', ' ORDER BY u.ord)
        INTO pk_cols
        FROM pg_index i
        JOIN pg_class c ON i.indrelid = c.oid
        JOIN pg_namespace n ON c.relnamespace = n.oid
        JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS u(attnum, ord) ON true
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum
        WHERE n.nspname = source_schema
          AND c.relname = rec.relname
          AND i.indisprimary;

        IF pk_cols IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %I.%I ADD PRIMARY KEY (%s)',
                target_schema, rec.relname, pk_cols
            );
        END IF;

        -- 5d. Add UNIQUE constraints (auto-propagate to all partitions)
        FOR idx_rec IN
            SELECT con.conname,
                   string_agg(a.attname, ', ' ORDER BY u.ord) AS cols
            FROM pg_constraint con
            JOIN pg_class c ON con.conrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS u(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum
            WHERE n.nspname = source_schema
              AND c.relname = rec.relname
              AND con.contype = 'u'
            GROUP BY con.oid, con.conname
        LOOP
            EXECUTE format(
                'ALTER TABLE %I.%I ADD CONSTRAINT %I UNIQUE (%s)',
                target_schema, rec.relname, idx_rec.conname, idx_rec.cols
            );
        END LOOP;

        -- 5e. Create non-constraint indexes (regular indexes, partial indexes)
        --     Replaces schema name in pg_get_indexdef output; removes ONLY
        --     keyword so indexes propagate to all partitions.
        FOR idx_rec IN
            SELECT pg_get_indexdef(i.indexrelid) AS orig_def
            FROM pg_index i
            JOIN pg_class c ON i.indrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = source_schema
              AND c.relname = rec.relname
              AND NOT i.indisprimary
              AND NOT EXISTS (
                  SELECT 1 FROM pg_constraint con
                  WHERE con.conindid = i.indexrelid
              )
        LOOP
            -- Remove ONLY keyword (allows index to propagate to partitions)
            indexdef_sql := replace(
                idx_rec.orig_def,
                ' ONLY ' || quote_ident(source_schema) || '.',
                ' ' || quote_ident(target_schema) || '.'
            );
            -- Replace remaining schema references
            indexdef_sql := replace(
                indexdef_sql,
                quote_ident(source_schema) || '.',
                quote_ident(target_schema) || '.'
            );
            EXECUTE indexdef_sql;
        END LOOP;
    END LOOP;

    -- ── 6. Copy table comments for partitioned tables ───────────────────────
    --    LIKE copies comments for regular tables; partitioned tables need manual copy
    FOR rec IN
        SELECT c.relname, d.description
        FROM pg_description d
        JOIN pg_class c ON d.objoid = c.oid
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE n.nspname = source_schema
          AND d.objsubid = 0
          AND c.relkind = 'p'
          AND d.description IS NOT NULL
    LOOP
        EXECUTE format(
            'COMMENT ON TABLE %I.%I IS %L',
            target_schema, rec.relname, rec.description
        );
    END LOOP;

END;
$$;

COMMENT ON FUNCTION public.clone_schema(TEXT, TEXT) IS
    'Clones all tables, indexes, constraints, sequences and partitions from source_schema to target_schema. Used for automated tenant provisioning.';


-- ══════════════════════════════════════════════════════════════════════════════
-- Part 2: Create tenant_template schema
-- ══════════════════════════════════════════════════════════════════════════════
-- Contains all tenant tables in their final state (V001–V006 combined).
-- This is the source schema for clone_schema() when provisioning new tenants.
-- IMPORTANT: Apply all future tenant migrations to this schema as well.

CREATE SCHEMA IF NOT EXISTS tenant_template;
SET search_path TO tenant_template;

-- ── documents (partitioned table — V001 + V005 final state) ─────────────────

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

    -- CHECK constraints
    CONSTRAINT chk_documents_status CHECK (
        status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'AUTHORIZED', 'NOTIFIED', 'REJECTED', 'FAILED', 'RETRY', 'VOIDED')
    ),
    CONSTRAINT chk_documents_origin CHECK (
        (request_origin = 'JSON' AND request_payload IS NOT NULL) OR
        (request_origin = 'XML_RAW' AND original_xml IS NOT NULL)
    )
) PARTITION BY RANGE (issue_date);

-- Default partition (catches data outside defined monthly ranges)
CREATE TABLE documents_default PARTITION OF documents DEFAULT;

-- Monthly partitions for current year (dynamic based on execution date)
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

-- Indexes (V005 + V006 final state, excluding idx_documents_status dropped by V006)
CREATE INDEX idx_documents_issue_date       ON documents (issue_date DESC);
CREATE INDEX idx_documents_recipient        ON documents (recipient_id);
CREATE INDEX idx_documents_retry            ON documents (status, next_retry_at) WHERE status = 'RETRY';
CREATE INDEX idx_documents_access_key       ON documents (access_key) WHERE access_key IS NOT NULL;
CREATE INDEX idx_documents_type_date        ON documents (document_type, issue_date DESC);
CREATE INDEX idx_documents_origin           ON documents (request_origin);
CREATE INDEX idx_documents_created_at       ON documents (created_at DESC);
-- V006 optimized indexes
CREATE INDEX idx_documents_pending          ON documents (status, next_retry_at) WHERE status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'RETRY');
CREATE INDEX idx_documents_status_type_date ON documents (status, document_type, issue_date DESC);

COMMENT ON TABLE documents IS 'Range-partitioned by issue_date (monthly). See V005.';


-- ── outbox (V002) ───────────────────────────────────────────────────────────

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


-- ── webhook_deliveries (V003, FK dropped by V005) ──────────────────────────

CREATE TABLE webhook_deliveries (
    webhook_delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL,

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


-- ── audit_log (V004) ────────────────────────────────────────────────────────

CREATE TABLE audit_log (
    audit_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(100),
    action      VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id   UUID NOT NULL,
    old_values  JSONB,
    new_values  JSONB,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);


-- ── Reset search_path ───────────────────────────────────────────────────────
SET search_path TO public;

COMMIT;

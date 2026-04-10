-- V006: Add partial index for in-flight documents + composite index for list queries
--
-- Purpose:
--   1. Partial index on 'status' for active/in-flight documents only.
--      The majority of documents reach terminal state (AUTHORIZED/NOTIFIED).
--      Pipeline queries and pollers only need the small set of in-flight docs.
--
--   2. Composite index (status, document_type, issue_date DESC) for the
--      listInvoices/portal queries that filter by these three columns.
--
-- The existing idx_documents_status (full index on status) is dropped because
-- the new partial index is strictly better for pipeline queries, and the
-- composite index covers list queries.
--
-- Prerequisites:
--   - Run inside each tenant schema: SET search_path TO tenant_xxx;
--   - V005 (partitioned table) must be applied first
--   - Safe to run on live systems (CONCURRENTLY not needed for new tables/partitions)

BEGIN;

-- ── 1. Drop the full status index (replaced by the partial + composite) ─────
DROP INDEX IF EXISTS idx_documents_status;

-- ── 2. Partial index: only in-flight documents ─────────────────────────────
-- Covers: findRetryReady(), pipeline status checks, dashboard "pending" filters
-- Size: tiny fraction of total rows (most docs are AUTHORIZED/NOTIFIED)
CREATE INDEX idx_documents_pending ON documents (status, next_retry_at)
    WHERE status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'RETRY');

-- ── 3. Composite index for list queries ────────────────────────────────────
-- Covers: GET /v1/invoices?status=X&date_from=Y&date_to=Z (most common API query)
-- Also benefits portal dashboard queries
CREATE INDEX idx_documents_status_type_date ON documents (status, document_type, issue_date DESC);

COMMIT;

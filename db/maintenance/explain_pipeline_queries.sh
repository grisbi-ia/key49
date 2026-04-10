#!/usr/bin/env bash
# ============================================================================
# Key49 — EXPLAIN ANALYZE for pipeline queries
# ============================================================================
# Runs EXPLAIN ANALYZE on the core query patterns used by the document pipeline
# to verify index usage and query plans.
#
# Usage:
#   ./explain_pipeline_queries.sh tenant_demo
#
# Environment:
#   KEY49_DB_HOST  (default: localhost)
#   KEY49_DB_PORT  (default: 5433)
#   KEY49_DB_NAME  (default: key49)
#   KEY49_DB_USER  (default: postgres)
#   PGPASSWORD     (must be set for non-interactive execution)
# ============================================================================

set -euo pipefail

SCHEMA="${1:?Usage: $0 <schema_name>}"

DB_HOST=${KEY49_DB_HOST:-localhost}
DB_PORT=${KEY49_DB_PORT:-5433}
DB_NAME=${KEY49_DB_NAME:-key49}
DB_USER=${KEY49_DB_USER:-postgres}

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME"

echo "$(date -Iseconds) [INFO] EXPLAIN ANALYZE for pipeline queries in schema: $SCHEMA"
echo ""

# Helper function
explain_query() {
    local label="$1"
    local query="$2"

    echo "=== $label ==="
    echo "SQL: $query"
    echo ""
    $PSQL -c "SET search_path TO $SCHEMA; EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $query" 2>&1
    echo ""
    echo "---"
    echo ""
}

# ── 1. PK lookup by document_id (pipeline consumers) ────────────────────────
# All consumers: em.find(Document.class, documentId)
SAMPLE_ID=$($PSQL -t -A -c "SET search_path TO $SCHEMA; SELECT document_id FROM documents LIMIT 1" 2>/dev/null || echo "")

if [ -z "$SAMPLE_ID" ]; then
    echo "$(date -Iseconds) [WARN] No documents found in $SCHEMA. Insert data first."
    exit 0
fi

explain_query "PK Lookup (pipeline consumers)" \
    "SELECT * FROM documents WHERE document_id = '$SAMPLE_ID'"

# ── 2. Find by access_key (DocumentRepository.findByAccessKey) ──────────────
SAMPLE_AK=$($PSQL -t -A -c "SET search_path TO $SCHEMA; SELECT access_key FROM documents WHERE access_key IS NOT NULL LIMIT 1" 2>/dev/null || echo "")

if [ -n "$SAMPLE_AK" ]; then
    explain_query "Find by access_key" \
        "SELECT * FROM documents WHERE access_key = '$SAMPLE_AK'"
fi

# ── 3. Find retry-ready (RetryPoller — every 5s) ────────────────────────────
explain_query "Find retry-ready (partial index)" \
    "SELECT * FROM documents WHERE status = 'RETRY' AND next_retry_at <= now()"

# ── 4. Find pending documents (in-flight pipeline) ──────────────────────────
explain_query "Find pending/in-flight (new partial index)" \
    "SELECT * FROM documents WHERE status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED')"

# ── 5. List invoices by date range (most common API query) ──────────────────
explain_query "List invoices — date range" \
    "SELECT * FROM documents WHERE document_type = '01' AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01' ORDER BY issue_date DESC LIMIT 20"

# ── 6. List invoices — date + status filter ─────────────────────────────────
explain_query "List invoices — date + status" \
    "SELECT * FROM documents WHERE document_type = '01' AND status = 'AUTHORIZED' AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01' ORDER BY issue_date DESC LIMIT 20"

# ── 7. Count for pagination ─────────────────────────────────────────────────
explain_query "Count for pagination" \
    "SELECT count(*) FROM documents WHERE document_type = '01' AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01'"

# ── 8. Find by idempotency_key (idempotency check) ─────────────────────────
explain_query "Find by idempotency_key" \
    "SELECT * FROM documents WHERE idempotency_key = 'test-nonexistent-key'"

# ── 9. Find by recipient_id ─────────────────────────────────────────────────
explain_query "Find by recipient_id" \
    "SELECT * FROM documents WHERE recipient_id = '0991234567001' ORDER BY issue_date DESC LIMIT 20"

# ── 10. Outbox: find unpublished (OutboxPoller) ─────────────────────────────
explain_query "Outbox — find unpublished" \
    "SELECT * FROM outbox WHERE published = false ORDER BY created_at ASC LIMIT 100"

# ── 11. Webhook: pending retries ────────────────────────────────────────────
explain_query "Webhook — pending retries" \
    "SELECT * FROM webhook_deliveries WHERE status = 'pending' AND next_attempt_at IS NOT NULL AND next_attempt_at <= now() ORDER BY next_attempt_at ASC"

echo "$(date -Iseconds) [INFO] Pipeline query analysis complete."

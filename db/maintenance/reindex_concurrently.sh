#!/usr/bin/env bash
# ============================================================================
# Key49 — REINDEX CONCURRENTLY (zero-downtime reindexing)
# ============================================================================
# Rebuilds all indexes in tenant schemas using REINDEX CONCURRENTLY,
# which does NOT lock reads or writes during the rebuild.
#
# When to run:
#   - After large data migrations or bulk imports
#   - When index bloat is detected (via monitor_bloat.sh)
#   - Periodically (monthly recommended for high-write tables)
#
# Usage:
#   ./reindex_concurrently.sh                      # all active tenants
#   ./reindex_concurrently.sh --schema tenant_demo  # single schema
#   ./reindex_concurrently.sh --table documents     # specific table across all schemas
#
# Environment:
#   KEY49_DB_HOST  (default: localhost)
#   KEY49_DB_PORT  (default: 5433)
#   KEY49_DB_NAME  (default: key49)
#   KEY49_DB_USER  (default: postgres)
#   PGPASSWORD     (must be set for non-interactive execution)
#
# Cron example (1st Sunday of every month at 04:00):
#   0 4 1-7 * 0 /opt/key49/db/maintenance/reindex_concurrently.sh >> /var/log/key49/reindex.log 2>&1
#
# NOTE: REINDEX CONCURRENTLY cannot run inside a transaction block.
#       This script uses individual statements (no BEGIN/COMMIT wrapping).
# ============================================================================

set -euo pipefail

DB_HOST=${KEY49_DB_HOST:-localhost}
DB_PORT=${KEY49_DB_PORT:-5433}
DB_NAME=${KEY49_DB_NAME:-key49}
DB_USER=${KEY49_DB_USER:-postgres}

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME"

SINGLE_SCHEMA=""
SINGLE_TABLE=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --schema)
            SINGLE_SCHEMA="$2"
            shift 2
            ;;
        --table)
            SINGLE_TABLE="$2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--schema <schema_name>] [--table <table_name>]"
            exit 1
            ;;
    esac
done

echo "$(date -Iseconds) [INFO] Starting REINDEX CONCURRENTLY"

# Build list of schemas
if [ -n "$SINGLE_SCHEMA" ]; then
    SCHEMAS="$SINGLE_SCHEMA"
else
    SCHEMAS="public"$'\n'$($PSQL -t -A -c "SELECT schema_name FROM tenants WHERE status = 'active' ORDER BY schema_name")
fi

if [ -z "$SCHEMAS" ]; then
    echo "$(date -Iseconds) [WARN] No schemas found"
    exit 0
fi

TOTAL_INDEXES=0
ERRORS=0

for SCHEMA in $SCHEMAS; do
    echo "$(date -Iseconds) [INFO] Processing schema: $SCHEMA"

    # Build table filter
    TABLE_FILTER=""
    if [ -n "$SINGLE_TABLE" ]; then
        TABLE_FILTER="AND t.relname = '$SINGLE_TABLE'"
    fi

    # Get all indexes in this schema (excluding partitioned parent indexes, those cascade)
    INDEXES=$($PSQL -t -A -c "
        SELECT i.relname AS index_name
        FROM pg_index ix
        JOIN pg_class i ON ix.indexrelid = i.oid
        JOIN pg_class t ON ix.indrelid = t.oid
        JOIN pg_namespace n ON t.relnamespace = n.oid
        WHERE n.nspname = '$SCHEMA'
          AND i.relkind = 'i'
          $TABLE_FILTER
        ORDER BY i.relname
    ")

    if [ -z "$INDEXES" ]; then
        echo "$(date -Iseconds) [INFO]   No indexes found — skipping"
        continue
    fi

    for INDEX in $INDEXES; do
        echo -n "$(date -Iseconds) [INFO]   REINDEX INDEX CONCURRENTLY $SCHEMA.$INDEX ... "

        # REINDEX CONCURRENTLY cannot be inside a transaction
        if $PSQL -c "REINDEX INDEX CONCURRENTLY \"$SCHEMA\".\"$INDEX\";" 2>&1 | tail -1; then
            TOTAL_INDEXES=$((TOTAL_INDEXES + 1))
        else
            echo "ERROR"
            ERRORS=$((ERRORS + 1))
        fi
    done
done

echo ""
echo "$(date -Iseconds) [INFO] Done. Reindexed $TOTAL_INDEXES indexes, $ERRORS errors."

# Show index sizes after reindex
echo ""
echo "=== Index Sizes (top 20) ==="
$PSQL -c "
    SELECT
        n.nspname AS schema,
        t.relname AS table_name,
        i.relname AS index_name,
        pg_size_pretty(pg_relation_size(i.oid)) AS index_size
    FROM pg_class i
    JOIN pg_index ix ON ix.indexrelid = i.oid
    JOIN pg_class t ON ix.indrelid = t.oid
    JOIN pg_namespace n ON t.relnamespace = n.oid
    WHERE n.nspname = 'public' OR n.nspname LIKE 'tenant_%'
    ORDER BY pg_relation_size(i.oid) DESC
    LIMIT 20;
"

if [ "$ERRORS" -gt 0 ]; then
    exit 1
fi

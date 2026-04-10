#!/usr/bin/env bash
# ============================================================================
# Key49 — Tune autovacuum for the documents table (per-tenant)
# ============================================================================
# Sets aggressive autovacuum parameters on the documents table in all
# active tenant schemas. This is especially important for partitioned tables
# since autovacuum works at the partition level.
#
# Parameters applied:
#   autovacuum_vacuum_scale_factor  = 0.05  (default 0.2 — vacuum at 5% dead tuples)
#   autovacuum_analyze_scale_factor = 0.05  (default 0.1 — analyze at 5% changes)
#   autovacuum_vacuum_cost_delay    = 10    (default 2ms — slightly throttled)
#
# Usage:
#   ./tune_autovacuum.sh              # all active tenants
#   ./tune_autovacuum.sh tenant_demo  # single tenant schema
#
# Environment:
#   KEY49_DB_HOST  (default: localhost)
#   KEY49_DB_PORT  (default: 5433)
#   KEY49_DB_NAME  (default: key49)
#   KEY49_DB_USER  (default: postgres)
#   PGPASSWORD     (must be set for non-interactive execution)
# ============================================================================

set -euo pipefail

DB_HOST=${KEY49_DB_HOST:-localhost}
DB_PORT=${KEY49_DB_PORT:-5433}
DB_NAME=${KEY49_DB_NAME:-key49}
DB_USER=${KEY49_DB_USER:-postgres}

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -v ON_ERROR_STOP=1"

SINGLE_SCHEMA="${1:-}"

echo "$(date -Iseconds) [INFO] Tuning autovacuum for documents tables"

# Build list of schemas
if [ -n "$SINGLE_SCHEMA" ]; then
    SCHEMAS="$SINGLE_SCHEMA"
else
    SCHEMAS=$($PSQL -t -A -c "SELECT schema_name FROM tenants WHERE status = 'active' ORDER BY schema_name")
fi

if [ -z "$SCHEMAS" ]; then
    echo "$(date -Iseconds) [WARN] No tenant schemas found"
    exit 0
fi

TOTAL=0

for SCHEMA in $SCHEMAS; do
    echo "$(date -Iseconds) [INFO] Processing schema: $SCHEMA"

    # Get all partition tables (children) + parent (if not partitioned, just the table)
    TABLES=$($PSQL -t -A -c "
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE n.nspname = '$SCHEMA'
          AND c.relname LIKE 'documents%'
          AND c.relkind IN ('r', 'p')
        ORDER BY c.relname
    ")

    for TABLE in $TABLES; do
        echo -n "$(date -Iseconds) [INFO]   ALTER TABLE $SCHEMA.$TABLE ... "

        $PSQL -c "
            ALTER TABLE \"$SCHEMA\".\"$TABLE\" SET (
                autovacuum_vacuum_scale_factor = 0.05,
                autovacuum_analyze_scale_factor = 0.05,
                autovacuum_vacuum_cost_delay = 10
            );
        " > /dev/null 2>&1

        echo "OK"
        TOTAL=$((TOTAL + 1))
    done
done

echo "$(date -Iseconds) [INFO] Done. Tuned $TOTAL tables across $(echo "$SCHEMAS" | wc -l) schemas."

# Verify settings
echo ""
echo "$(date -Iseconds) [INFO] Verification — current autovacuum settings:"
$PSQL -c "
    SELECT n.nspname AS schema, c.relname AS table_name,
           pg_catalog.array_to_string(c.reloptions, ', ') AS options
    FROM pg_class c
    JOIN pg_namespace n ON c.relnamespace = n.oid
    WHERE c.relname LIKE 'documents%'
      AND c.reloptions IS NOT NULL
    ORDER BY n.nspname, c.relname;
"

#!/usr/bin/env bash
# ============================================================================
# Key49 — VACUUM ANALYZE for all tenant schemas
# ============================================================================
# Runs VACUUM ANALYZE on all tables in all active tenant schemas,
# plus the public schema (tenants, api_keys).
#
# Usage:
#   ./vacuum_analyze.sh                   # all active tenants + public
#   ./vacuum_analyze.sh --schema tenant_demo  # single schema
#   ./vacuum_analyze.sh --full            # VACUUM FULL (requires downtime!)
#
# Environment:
#   KEY49_DB_HOST  (default: localhost)
#   KEY49_DB_PORT  (default: 5433)
#   KEY49_DB_NAME  (default: key49)
#   KEY49_DB_USER  (default: postgres)
#   PGPASSWORD     (must be set for non-interactive execution)
#
# Cron example (daily at 03:00):
#   0 3 * * * /opt/key49/db/maintenance/vacuum_analyze.sh >> /var/log/key49/vacuum.log 2>&1
# ============================================================================

set -euo pipefail

DB_HOST=${KEY49_DB_HOST:-localhost}
DB_PORT=${KEY49_DB_PORT:-5433}
DB_NAME=${KEY49_DB_NAME:-key49}
DB_USER=${KEY49_DB_USER:-postgres}

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -v ON_ERROR_STOP=1"

VACUUM_FULL=false
SINGLE_SCHEMA=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --full)
            VACUUM_FULL=true
            shift
            ;;
        --schema)
            SINGLE_SCHEMA="$2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--full] [--schema <schema_name>]"
            exit 1
            ;;
    esac
done

VACUUM_CMD="VACUUM ANALYZE"
if [ "$VACUUM_FULL" = true ]; then
    VACUUM_CMD="VACUUM FULL ANALYZE"
    echo "$(date -Iseconds) [WARN] Running VACUUM FULL — this acquires exclusive locks!"
fi

echo "$(date -Iseconds) [INFO] Starting $VACUUM_CMD on key49"

# Build list of schemas to process
if [ -n "$SINGLE_SCHEMA" ]; then
    SCHEMAS="$SINGLE_SCHEMA"
else
    # Public schema + all active tenant schemas
    SCHEMAS="public"$'\n'$($PSQL -t -A -c "SELECT schema_name FROM tenants WHERE status = 'active' ORDER BY schema_name")
fi

if [ -z "$SCHEMAS" ]; then
    echo "$(date -Iseconds) [WARN] No schemas found to process"
    exit 0
fi

TOTAL_TABLES=0
ERRORS=0

for SCHEMA in $SCHEMAS; do
    echo "$(date -Iseconds) [INFO] Processing schema: $SCHEMA"

    # Get all user tables in this schema
    TABLES=$($PSQL -t -A -c "
        SELECT tablename FROM pg_tables
        WHERE schemaname = '$SCHEMA'
        ORDER BY tablename
    ")

    if [ -z "$TABLES" ]; then
        echo "$(date -Iseconds) [WARN]   No tables found in $SCHEMA — skipping"
        continue
    fi

    for TABLE in $TABLES; do
        QUALIFIED="\"$SCHEMA\".\"$TABLE\""
        echo -n "$(date -Iseconds) [INFO]   $VACUUM_CMD $QUALIFIED ... "

        if $PSQL -c "$VACUUM_CMD $QUALIFIED;" 2>&1; then
            echo "OK"
            TOTAL_TABLES=$((TOTAL_TABLES + 1))
        else
            echo "ERROR"
            ERRORS=$((ERRORS + 1))
        fi
    done
done

echo "$(date -Iseconds) [INFO] Done. Processed $TOTAL_TABLES tables, $ERRORS errors."

if [ "$ERRORS" -gt 0 ]; then
    exit 1
fi

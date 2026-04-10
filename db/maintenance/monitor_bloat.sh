#!/usr/bin/env bash
# ============================================================================
# Key49 — Monitor table bloat and dead tuples
# ============================================================================
# Reports dead tuple counts and estimated bloat for all tables in active
# tenant schemas. Useful for verifying autovacuum effectiveness.
#
# Usage:
#   ./monitor_bloat.sh                    # all active tenants
#   ./monitor_bloat.sh --schema tenant_demo   # single schema
#   ./monitor_bloat.sh --threshold 10000  # only show tables with > 10k dead tuples
#
# Environment:
#   KEY49_DB_HOST  (default: localhost)
#   KEY49_DB_PORT  (default: 5433)
#   KEY49_DB_NAME  (default: key49)
#   KEY49_DB_USER  (default: postgres)
#   PGPASSWORD     (must be set for non-interactive execution)
#
# Cron example (weekly on Sunday at 04:00):
#   0 4 * * 0 /opt/key49/db/maintenance/monitor_bloat.sh >> /var/log/key49/bloat.log 2>&1
# ============================================================================

set -euo pipefail

DB_HOST=${KEY49_DB_HOST:-localhost}
DB_PORT=${KEY49_DB_PORT:-5433}
DB_NAME=${KEY49_DB_NAME:-key49}
DB_USER=${KEY49_DB_USER:-postgres}

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME"

THRESHOLD=0
SINGLE_SCHEMA=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --threshold)
            THRESHOLD="$2"
            shift 2
            ;;
        --schema)
            SINGLE_SCHEMA="$2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--threshold <dead_tuples>] [--schema <schema_name>]"
            exit 1
            ;;
    esac
done

echo "$(date -Iseconds) [INFO] Monitoring table bloat (threshold: $THRESHOLD dead tuples)"
echo ""

# ---- Section 1: Dead tuples per table (from pg_stat_user_tables) ----
echo "=== Dead Tuples Report ==="
echo ""

if [ -n "$SINGLE_SCHEMA" ]; then
    SCHEMA_FILTER="AND schemaname = '$SINGLE_SCHEMA'"
else
    SCHEMA_FILTER="AND (schemaname = 'public' OR schemaname LIKE 'tenant_%')"
fi

$PSQL -c "
    SELECT
        schemaname AS schema,
        relname AS table_name,
        n_live_tup AS live_tuples,
        n_dead_tup AS dead_tuples,
        CASE WHEN n_live_tup > 0
            THEN ROUND(n_dead_tup::NUMERIC / n_live_tup * 100, 2)
            ELSE 0
        END AS dead_pct,
        last_vacuum,
        last_autovacuum,
        last_analyze,
        last_autoanalyze
    FROM pg_stat_user_tables
    WHERE n_dead_tup >= $THRESHOLD
      $SCHEMA_FILTER
    ORDER BY n_dead_tup DESC
    LIMIT 50;
"

# ---- Section 2: Estimated bloat using statistical estimation ----
echo ""
echo "=== Estimated Table Bloat ==="
echo ""

$PSQL -c "
    SELECT
        schemaname AS schema,
        tablename AS table_name,
        pg_size_pretty(pg_total_relation_size(schemaname || '.' || tablename)) AS total_size,
        pg_size_pretty(pg_relation_size(schemaname || '.' || tablename)) AS table_size,
        pg_size_pretty(
            pg_total_relation_size(schemaname || '.' || tablename)
            - pg_relation_size(schemaname || '.' || tablename)
        ) AS index_size
    FROM pg_tables
    WHERE schemaname = 'public' OR schemaname LIKE 'tenant_%'
    ORDER BY pg_total_relation_size(schemaname || '.' || tablename) DESC
    LIMIT 30;
"

# ---- Section 3: Autovacuum activity ----
echo ""
echo "=== Autovacuum Activity (last 24h) ==="
echo ""

$PSQL -c "
    SELECT
        schemaname AS schema,
        relname AS table_name,
        vacuum_count,
        autovacuum_count,
        analyze_count,
        autoanalyze_count,
        last_autovacuum,
        last_autoanalyze
    FROM pg_stat_user_tables
    WHERE (last_autovacuum >= now() - INTERVAL '24 hours'
        OR last_autoanalyze >= now() - INTERVAL '24 hours')
      AND (schemaname = 'public' OR schemaname LIKE 'tenant_%')
    ORDER BY last_autovacuum DESC NULLS LAST;
"

# ---- Section 4: Tables that need vacuuming ----
echo ""
echo "=== Tables Needing Vacuum (dead > 5% of live) ==="
echo ""

$PSQL -c "
    SELECT
        schemaname AS schema,
        relname AS table_name,
        n_live_tup AS live,
        n_dead_tup AS dead,
        ROUND(n_dead_tup::NUMERIC / GREATEST(n_live_tup, 1) * 100, 2) AS dead_pct,
        last_autovacuum
    FROM pg_stat_user_tables
    WHERE n_dead_tup > n_live_tup * 0.05
      AND n_dead_tup > 100
      AND (schemaname = 'public' OR schemaname LIKE 'tenant_%')
    ORDER BY dead_pct DESC;
"

echo ""
echo "$(date -Iseconds) [INFO] Bloat monitoring complete."

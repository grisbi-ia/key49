#!/usr/bin/env bash
# ============================================================================
# Key49 — Top queries report from pg_stat_statements
# ============================================================================
# Extracts the top N slowest and most frequent queries from pg_stat_statements.
# Requires the pg_stat_statements extension to be enabled.
#
# Usage:
#   ./top_queries.sh                  # default: top 10
#   ./top_queries.sh 20               # top 20
#   ./top_queries.sh --reset          # reset stats (use after analysis)
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

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME"

TOP_N=10
RESET=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --reset)
            RESET=true
            shift
            ;;
        [0-9]*)
            TOP_N="$1"
            shift
            ;;
        *)
            echo "Usage: $0 [N] [--reset]"
            exit 1
            ;;
    esac
done

# Verify pg_stat_statements is available
EXTENSION_EXISTS=$($PSQL -t -A -c "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'" 2>/dev/null || echo "0")
if [ "$EXTENSION_EXISTS" != "1" ]; then
    echo "$(date -Iseconds) [ERROR] pg_stat_statements extension is not installed."
    echo ""
    echo "To enable pg_stat_statements:"
    echo ""
    echo "  === Local PostgreSQL (development) ==="
    echo "  1. Edit postgresql.conf:"
    echo "     shared_preload_libraries = 'pg_stat_statements'"
    echo "     pg_stat_statements.track = all"
    echo "  2. Restart PostgreSQL: sudo systemctl restart postgresql"
    echo "  3. Create extension: CREATE EXTENSION pg_stat_statements;"
    echo ""
    echo "  === Docker PostgreSQL (production) ==="
    echo "  Add to docker-compose.yml under postgres service:"
    echo "    command: >"
    echo "      postgres"
    echo "      -c shared_preload_libraries=pg_stat_statements"
    echo "      -c pg_stat_statements.track=all"
    echo "  Then connect and run: CREATE EXTENSION pg_stat_statements;"
    exit 1
fi

if [ "$RESET" = true ]; then
    echo "$(date -Iseconds) [INFO] Resetting pg_stat_statements statistics..."
    $PSQL -c "SELECT pg_stat_statements_reset();" > /dev/null
    echo "$(date -Iseconds) [INFO] Done. Statistics reset."
    exit 0
fi

echo "$(date -Iseconds) [INFO] pg_stat_statements report — top $TOP_N queries"
echo ""

# ── Section 1: Top N by total time ──────────────────────────────────────────
echo "=== Top $TOP_N Queries by Total Time ==="
echo ""
$PSQL -x -c "
    SELECT
        queryid,
        calls,
        ROUND(total_exec_time::NUMERIC, 2) AS total_time_ms,
        ROUND(mean_exec_time::NUMERIC, 2) AS avg_time_ms,
        ROUND(max_exec_time::NUMERIC, 2) AS max_time_ms,
        ROUND(min_exec_time::NUMERIC, 2) AS min_time_ms,
        ROUND(stddev_exec_time::NUMERIC, 2) AS stddev_ms,
        rows,
        LEFT(query, 200) AS query_preview
    FROM pg_stat_statements
    WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB_NAME')
      AND query NOT LIKE '%pg_stat%'
    ORDER BY total_exec_time DESC
    LIMIT $TOP_N;
"

# ── Section 2: Top N by call frequency ──────────────────────────────────────
echo ""
echo "=== Top $TOP_N Queries by Call Frequency ==="
echo ""
$PSQL -x -c "
    SELECT
        queryid,
        calls,
        ROUND(total_exec_time::NUMERIC, 2) AS total_time_ms,
        ROUND(mean_exec_time::NUMERIC, 2) AS avg_time_ms,
        rows,
        LEFT(query, 200) AS query_preview
    FROM pg_stat_statements
    WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB_NAME')
      AND query NOT LIKE '%pg_stat%'
    ORDER BY calls DESC
    LIMIT $TOP_N;
"

# ── Section 3: Top N by avg time (slow individual queries) ──────────────────
echo ""
echo "=== Top $TOP_N Queries by Average Time (min 10 calls) ==="
echo ""
$PSQL -x -c "
    SELECT
        queryid,
        calls,
        ROUND(mean_exec_time::NUMERIC, 2) AS avg_time_ms,
        ROUND(max_exec_time::NUMERIC, 2) AS max_time_ms,
        rows,
        LEFT(query, 200) AS query_preview
    FROM pg_stat_statements
    WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB_NAME')
      AND calls >= 10
      AND query NOT LIKE '%pg_stat%'
    ORDER BY mean_exec_time DESC
    LIMIT $TOP_N;
"

# ── Section 4: Summary stats ────────────────────────────────────────────────
echo ""
echo "=== Summary ==="
echo ""
$PSQL -c "
    SELECT
        COUNT(*) AS total_distinct_queries,
        SUM(calls) AS total_calls,
        ROUND(SUM(total_exec_time)::NUMERIC / 1000, 2) AS total_exec_time_s,
        ROUND(AVG(mean_exec_time)::NUMERIC, 2) AS global_avg_time_ms
    FROM pg_stat_statements
    WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB_NAME');
"

echo ""
echo "$(date -Iseconds) [INFO] Report complete. Use '$0 --reset' to clear stats for a new measurement window."

#!/usr/bin/env bash
# ============================================================================
# Key49 — Create monthly partitions for the documents table
# ============================================================================
# Creates future monthly partitions for all active tenant schemas.
# Designed to run monthly via cron (e.g., on the 1st of each month).
#
# Usage:
#   ./create_monthly_partitions.sh              # next 3 months (default)
#   ./create_monthly_partitions.sh 6            # next 6 months
#
# Environment:
#   KEY49_DB_HOST  (default: localhost)
#   KEY49_DB_PORT  (default: 5433)
#   KEY49_DB_NAME  (default: key49)
#   KEY49_DB_USER  (default: postgres)
#   PGPASSWORD     (must be set for non-interactive execution)
#
# Cron example (1st of every month at 02:00):
#   0 2 1 * * /opt/key49/db/maintenance/create_monthly_partitions.sh 3 >> /var/log/key49/partitions.log 2>&1
# ============================================================================

set -euo pipefail

MONTHS_AHEAD=${1:-3}
DB_HOST=${KEY49_DB_HOST:-localhost}
DB_PORT=${KEY49_DB_PORT:-5433}
DB_NAME=${KEY49_DB_NAME:-key49}
DB_USER=${KEY49_DB_USER:-postgres}

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -v ON_ERROR_STOP=1"

echo "$(date -Iseconds) [INFO] Creating partitions for next $MONTHS_AHEAD months"

# Get all active tenant schemas
SCHEMAS=$($PSQL -t -A -c "SELECT schema_name FROM tenants WHERE status = 'active' ORDER BY schema_name")

if [ -z "$SCHEMAS" ]; then
    echo "$(date -Iseconds) [WARN] No active tenant schemas found"
    exit 0
fi

TOTAL_CREATED=0

for SCHEMA in $SCHEMAS; do
    echo "$(date -Iseconds) [INFO] Processing schema: $SCHEMA"

    # Verify the documents table is actually partitioned
    IS_PARTITIONED=$($PSQL -t -A -c "
        SELECT count(*) FROM pg_partitioned_table pt
        JOIN pg_class c ON pt.partrelid = c.oid
        JOIN pg_namespace n ON c.relnamespace = n.oid
        WHERE c.relname = 'documents' AND n.nspname = '$SCHEMA'
    ")

    if [ "$IS_PARTITIONED" != "1" ]; then
        echo "$(date -Iseconds) [WARN]   documents table in $SCHEMA is not partitioned — skipping"
        continue
    fi

    for i in $(seq 0 "$MONTHS_AHEAD"); do
        TARGET_DATE=$(date -d "+${i} months" +%Y-%m-01)
        YEAR=$(date -d "$TARGET_DATE" +%Y)
        MONTH=$(date -d "$TARGET_DATE" +%m)
        NEXT_MONTH=$(date -d "$TARGET_DATE +1 month" +%Y-%m-01)
        PARTITION_NAME="documents_${YEAR}_${MONTH}"

        $PSQL -c "
            SET search_path TO '$SCHEMA';
            DO \$\$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM pg_class c
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE c.relname = '$PARTITION_NAME' AND n.nspname = '$SCHEMA'
                ) THEN
                    EXECUTE format(
                        'CREATE TABLE %I PARTITION OF documents FOR VALUES FROM (%L) TO (%L)',
                        '$PARTITION_NAME', '$TARGET_DATE'::date, '$NEXT_MONTH'::date
                    );
                    RAISE NOTICE 'Created partition %', '$PARTITION_NAME';
                ELSE
                    RAISE NOTICE 'Partition % already exists — skipping', '$PARTITION_NAME';
                END IF;
            END \$\$;
        " 2>&1 | grep -i "NOTICE" | sed "s/^/$(date -Iseconds) [INFO]   /"

        # Count newly created partitions
        EXISTS=$($PSQL -t -A -c "
            SELECT count(*) FROM pg_class c
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE c.relname = '$PARTITION_NAME' AND n.nspname = '$SCHEMA'
        ")
        if [ "$EXISTS" = "1" ]; then
            TOTAL_CREATED=$((TOTAL_CREATED + 1))
        fi
    done
done

echo "$(date -Iseconds) [INFO] Done. Verified/created $TOTAL_CREATED partitions across $(echo "$SCHEMAS" | wc -l) schemas."

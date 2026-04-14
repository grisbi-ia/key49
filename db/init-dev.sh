#!/usr/bin/env bash
# ─────────────────────────────────────────────────
# Key49 — Script de inicialización de BD para desarrollo
# Uso: ./db/init-dev.sh
# Requiere: PostgreSQL corriendo en localhost:5433
# ─────────────────────────────────────────────────
set -euo pipefail

DB_HOST="${KEY49_DB_HOST:-localhost}"
DB_PORT="${KEY49_DB_PORT:-5433}"
DB_NAME="${KEY49_DB_NAME:-key49}"
DB_USER="${KEY49_DB_USER:-postgres}"
export PGPASSWORD="${KEY49_DB_PASSWORD:-1234abcd}"

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -v ON_ERROR_STOP=1"

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Key49 — Inicialización de BD para desarrollo       ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "→ Conectando a $DB_HOST:$DB_PORT/$DB_NAME..."

# ── 1. Crear tablas del esquema public ──
echo ""
echo "▸ [1/6] Creando tablas en esquema public..."
$PSQL -f db/migrations/public/V001__create_tenants.sql 2>/dev/null || echo "  (tenants ya existe, continuando)"
$PSQL -f db/migrations/public/V002__create_api_keys.sql 2>/dev/null || echo "  (api_keys ya existe, continuando)"
for f in db/migrations/public/V003__add_granular_rate_limits.sql \
         db/migrations/public/V004__create_audit_log.sql \
         db/migrations/public/V005__add_pending_certificate.sql; do
    $PSQL -f "$f" 2>/dev/null || echo "  ($(basename $f) ya aplicada)"
done
echo "  ✓ Tablas public listas"

# ── 2. Crear clone_schema() y tenant_template ──
echo ""
echo "▸ [2/6] Creando función clone_schema() y esquema tenant_template..."
$PSQL -f db/migrations/public/V006__create_clone_schema_and_template.sql 2>/dev/null || echo "  (clone_schema/template ya existe)"
echo "  ✓ clone_schema() y tenant_template listos"

# ── 3. Crear tenant de demo ──
TENANT_ID="a1b2c3d4-e5f6-7890-abcd-ef1234567890"
SCHEMA_NAME="tenant_demo"
echo ""
echo "▸ [3/6] Creando tenant de demo..."
$PSQL <<SQL
INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, environment, schema_name, rate_limit_rpm)
VALUES (
    '$TENANT_ID',
    '1790016919001',
    'Empresa Demo S.A.',
    'Demo Corp',
    'Av. Amazonas N24-345, Quito',
    'test',
    '$SCHEMA_NAME',
    200
)
ON CONFLICT (tenant_id) DO NOTHING;
SQL
echo "  ✓ Tenant: Empresa Demo S.A. (RUC: 1790016919001)"

# ── 4. Crear esquema del tenant vía clone_schema ──
echo ""
echo "▸ [4/6] Creando esquema $SCHEMA_NAME vía clone_schema()..."
$PSQL -c "SELECT clone_schema('tenant_template', '$SCHEMA_NAME');" 2>/dev/null || echo "  ($SCHEMA_NAME ya existe, continuando)"
echo "  ✓ Esquema $SCHEMA_NAME listo con todas las tablas"

# ── 5. Crear API key de demo ──
# API key conocida para desarrollo: k49_DemoKey49DevLocalTest0000
# SHA-256 hash se calcula aquí
DEMO_RAW_KEY="k49_DemoKey49DevLocalTest0000"
DEMO_HASH=$(echo -n "$DEMO_RAW_KEY" | sha256sum | cut -d' ' -f1)
echo ""
echo "▸ [5/6] Creando API key de demo..."
$PSQL <<SQL
INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status)
VALUES (
    'b1b2c3d4-e5f6-7890-abcd-ef1234567890',
    '$TENANT_ID',
    'k49',
    '$DEMO_HASH',
    'Dev Local Key',
    '*',
    'active'
)
ON CONFLICT (api_key_id) DO NOTHING;
SQL
echo "  ✓ API key creada"

# ── 6. Crear bucket en MinIO ──
echo ""
echo "▸ [6/6] Creando bucket en MinIO..."
if command -v mc &>/dev/null; then
    mc alias set key49minio http://localhost:9000 minioadmin minioadmin --api s3v4 2>/dev/null
    mc mb key49minio/key49-documents 2>/dev/null || echo "  (bucket ya existe)"
    echo "  ✓ Bucket key49-documents listo"
elif docker ps --format '{{.Names}}' | grep -q key49-minio; then
    docker exec key49-minio mc mb local/key49-documents 2>/dev/null || echo "  (bucket ya existe)"
    echo "  ✓ Bucket key49-documents listo"
else
    echo "  ⚠ MinIO no accesible — crea el bucket manualmente:"
    echo "    mc alias set key49 http://localhost:9000 minioadmin minioadmin"
    echo "    mc mb key49/key49-documents"
fi

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  ✅ Inicialización completada                       ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║                                                      ║"
echo "║  Tenant: Empresa Demo S.A.                          ║"
echo "║  RUC:    1790016919001                               ║"
echo "║  Schema: tenant_demo                                 ║"
echo "║                                                      ║"
echo "║  API Key: k49_DemoKey49DevLocalTest0000                ║"
echo "║                                                      ║"
echo "║  Arrancar la app:                                    ║"
echo "║    mvn quarkus:dev -pl key49-api                     ║"
echo "║                                                      ║"
echo "║  URLs:                                               ║"
echo "║    API:     http://localhost:8080/v1/invoices         ║"
echo "║    Portal:  http://localhost:8080/portal/login        ║"
echo "║    Health:  http://localhost:8080/q/health            ║"
echo "║    RabbitMQ: http://localhost:15672 (guest/guest)     ║"
echo "║    MinIO:   http://localhost:9001 (minioadmin)        ║"
echo "║                                                      ║"
echo "╚══════════════════════════════════════════════════════╝"

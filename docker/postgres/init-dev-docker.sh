#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  Key49 — Init Script para PostgreSQL Docker (desarrollo)    ║
# ║  Se ejecuta automáticamente al crear el volumen por 1ra vez ║
# ╚══════════════════════════════════════════════════════════════╝
set -e

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Key49 — Inicializando BD de desarrollo             ║"
echo "╚══════════════════════════════════════════════════════╝"

# ── 1. Crear tablas del esquema public ──
echo "▸ Creando tablas en esquema public..."
for f in /docker-entrypoint-initdb.d/migrations/public/V*.sql; do
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$f" 2>/dev/null || true
done
echo "  ✓ Tablas public listas"

# ── 2. Crear tenant de demo ──
TENANT_ID="a1b2c3d4-e5f6-7890-abcd-ef1234567890"
SCHEMA_NAME="tenant_demo"
echo "▸ Creando tenant de demo..."
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
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
echo "  ✓ Tenant demo creado"

# ── 3. Crear esquema del tenant vía clone_schema ──
echo "▸ Creando esquema $SCHEMA_NAME..."
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT clone_schema('tenant_template', '$SCHEMA_NAME');" 2>/dev/null || true
echo "  ✓ Esquema $SCHEMA_NAME listo"

# ── 4. Crear API key de demo ──
DEMO_RAW_KEY="k49_DemoKey49DevLocalTest0000"
DEMO_HASH=$(echo -n "$DEMO_RAW_KEY" | sha256sum | cut -d' ' -f1)
echo "▸ Creando API key de demo..."
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
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

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  ✅ BD de desarrollo inicializada                   ║"
echo "║  API Key: k49_DemoKey49DevLocalTest0000              ║"
echo "╚══════════════════════════════════════════════════════╝"

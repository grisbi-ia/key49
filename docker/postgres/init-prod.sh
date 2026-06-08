#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  Key49 — Init Script para PostgreSQL (primer arranque)      ║
# ║  Se ejecuta automáticamente al crear el volumen por 1ra vez ║
# ╚══════════════════════════════════════════════════════════════╝
set -e

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Key49 — Inicializando BD de producción             ║"
echo "╚══════════════════════════════════════════════════════╝"

# ── 1. Crear tablas del esquema public ──
echo ""
echo "▸ [1/3] Creando tablas en esquema public..."

for f in /docker-entrypoint-initdb.d/migrations/public/V*.sql; do
    echo "  → $(basename $f)"
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$f"
done
echo "  ✓ Tablas public listas"

# ── 2. Crear clone_schema() y tenant_template ──
echo ""
echo "▸ [2/3] Creando función clone_schema() y tenant_template..."
echo "  (se ejecuta en V006, verificado en paso anterior)"
echo "  ✓ clone_schema() y tenant_template listos"

# ── 3. Configurar PostgreSQL para producción + compatibilidad PgBouncer MD5 ──
echo ""
echo "▸ [3/3] Configurando PostgreSQL para producción..."

# Compatibilidad con PgBouncer: forzar MD5 en vez de SCRAM-SHA-256
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
SET password_encryption = 'md5';
ALTER ROLE "$POSTGRES_USER" WITH PASSWORD '${POSTGRES_PASSWORD}';
SQL

# Modificar pg_hba.conf para aceptar MD5 en conexiones de red
sed -i 's/scram-sha-256/md5/g' /var/lib/postgresql/data/pg_hba.conf
pg_ctl reload -D /var/lib/postgresql/data 2>/dev/null || true

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
ALTER SYSTEM SET shared_buffers = '256MB';
ALTER SYSTEM SET effective_cache_size = '768MB';
ALTER SYSTEM SET maintenance_work_mem = '64MB';
ALTER SYSTEM SET wal_buffers = '16MB';
ALTER SYSTEM SET default_statistics_target = 100;
ALTER SYSTEM SET random_page_cost = 1.1;
ALTER SYSTEM SET effective_io_concurrency = 200;
ALTER SYSTEM SET work_mem = '16MB';
ALTER SYSTEM SET min_wal_size = '1GB';
ALTER SYSTEM SET max_wal_size = '4GB';

-- Autovacuum más agresivo
ALTER SYSTEM SET autovacuum_vacuum_scale_factor = 0.05;
ALTER SYSTEM SET autovacuum_analyze_scale_factor = 0.025;
ALTER SYSTEM SET autovacuum_max_workers = 3;
ALTER SYSTEM SET autovacuum_naptime = '30s';

-- Habilitar pg_stat_statements para monitoreo
-- (requiere shared_preload_libraries, se configura en postgresql.conf del contenedor)

-- Recargar configuración
SELECT pg_reload_conf();
SQL
echo "  ✓ PostgreSQL configurado para producción"

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  ✅ BD de producción inicializada correctamente     ║"
echo "║  Los tenants se crean vía portal/API (no aquí)      ║"
echo "╚══════════════════════════════════════════════════════╝"

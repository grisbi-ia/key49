# Guía de Administración de Base de Datos — Key49

Operaciones comunes de administración de PostgreSQL para Key49.

---

## Índice

1. [Conexión](#conexión)
2. [Gestión de tenants](#gestión-de-tenants)
3. [Gestión de API Keys](#gestión-de-api-keys)
4. [Consulta de documentos](#consulta-de-documentos)
5. [Anulación de documentos](#anulación-de-documentos)
6. [Outbox y colas](#outbox-y-colas)
7. [Webhooks](#webhooks)
8. [Monitoreo y diagnóstico](#monitoreo-y-diagnóstico)
9. [Particionamiento de documents](#particionamiento-de-documents)
10. [Mantenimiento automatizado](#mantenimiento-automatizado)
11. [Mantenimiento manual](#mantenimiento-manual)

---

## Conexión

```bash
# Variables de conexión
DB_HOST=localhost
DB_PORT=5433
DB_NAME=key49
DB_USER=postgres
export PGPASSWORD=1234abcd

# Conectar
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME
```

---

## Gestión de tenants

### Crear un nuevo tenant

El proceso es manual y consta de 4 pasos:

#### Paso 1: Insertar en `public.tenants`

```sql
INSERT INTO tenants (
    tenant_id, ruc, legal_name, trade_name, main_address,
    environment, schema_name, rate_limit_rpm, status
) VALUES (
    gen_random_uuid(),
    '0991234567001',
    'Empresa Nueva S.A.',
    'NuevaCorp',
    'Guayaquil, Av. 9 de Octubre 100',
    'test',                  -- test | production
    'tenant_nuevacorp',      -- nombre del esquema (solo [a-z0-9_])
    100,                     -- requests por minuto
    'active'                 -- active | suspended | pending
)
RETURNING tenant_id, schema_name;
```

#### Paso 2: Crear el esquema PostgreSQL

```sql
CREATE SCHEMA tenant_nuevacorp;
```

#### Paso 3: Ejecutar scripts de migración

```bash
SCHEMA=tenant_nuevacorp

for script in \
  db/migrations/tenant/V001__create_documents.sql \
  db/migrations/tenant/V002__create_outbox.sql \
  db/migrations/tenant/V003__create_webhook_deliveries.sql \
  db/migrations/tenant/V004__create_audit_log.sql; do
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
      -c "SET search_path TO $SCHEMA;" \
      -f "$script"
done
```

#### Paso 4: Verificar tablas

```sql
SET search_path TO tenant_nuevacorp;
\dt

-- Resultado esperado:
--  audit_log          | table
--  documents          | table
--  outbox             | table
--  webhook_deliveries | table
```

### Listar tenants

```sql
SELECT tenant_id, ruc, legal_name, schema_name, environment, status, created_at
FROM tenants
ORDER BY created_at DESC;
```

### Listar tenants activos

```sql
SELECT tenant_id, ruc, legal_name, schema_name, rate_limit_rpm
FROM tenants
WHERE status = 'active'
ORDER BY legal_name;
```

### Suspender un tenant

```sql
UPDATE tenants
SET status = 'suspended', updated_at = now()
WHERE ruc = '0991234567001';
```

### Reactivar un tenant

```sql
UPDATE tenants
SET status = 'active', updated_at = now()
WHERE ruc = '0991234567001';
```

### Consultar estado del certificado

```sql
SELECT
    legal_name,
    certificate_subject,
    certificate_serial,
    certificate_expiration,
    EXTRACT(DAY FROM certificate_expiration - now()) AS days_remaining,
    CASE
        WHEN certificate_p12 IS NULL THEN 'NO CONFIGURADO'
        WHEN certificate_expiration < now() THEN 'EXPIRADO'
        WHEN certificate_expiration < now() + INTERVAL '30 days' THEN 'POR VENCER'
        ELSE 'VIGENTE'
    END AS certificate_status
FROM tenants
ORDER BY certificate_expiration ASC NULLS LAST;
```

### Actualizar configuración de webhook

```sql
UPDATE tenants
SET
    webhook_url = 'https://mi-app.com/webhooks/key49',
    webhook_secret = 'mi_secreto_seguro_123',
    updated_at = now()
WHERE ruc = '0991234567001';
```

---

## Gestión de API Keys

### Crear una API Key manualmente

```bash
# 1. Definir la key raw
RAW_KEY="fec_test_MiEmpresaApiKey2026Prod01"

# 2. Calcular el hash SHA-256
HASH=$(echo -n "$RAW_KEY" | sha256sum | cut -d' ' -f1)
echo "Hash: $HASH"

# 3. Insertar en la BD
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME <<SQL
INSERT INTO api_keys (
    api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status
) VALUES (
    gen_random_uuid(),
    (SELECT tenant_id FROM tenants WHERE ruc = '0991234567001'),
    '${RAW_KEY:0:8}',
    '$HASH',
    'API Key Producción',
    '*',
    'active'
);
SQL
```

### Listar API keys de un tenant

```sql
SELECT
    ak.api_key_id,
    ak.key_prefix,
    ak.name,
    ak.status,
    ak.last_used_at,
    ak.expires_at,
    ak.created_at
FROM api_keys ak
JOIN tenants t ON t.tenant_id = ak.tenant_id
WHERE t.ruc = '0991234567001'
ORDER BY ak.created_at DESC;
```

### Revocar una API Key

```sql
UPDATE api_keys
SET status = 'revoked'
WHERE api_key_id = 'UUID_DE_LA_KEY';
```

### Revocar todas las keys de un tenant

```sql
UPDATE api_keys
SET status = 'revoked'
WHERE tenant_id = (SELECT tenant_id FROM tenants WHERE ruc = '0991234567001')
  AND status = 'active';
```

### Verificar si una API Key existe y está activa

```bash
# Calcular hash de la key a verificar
RAW_KEY="fec_test_DemoKey49DevLocalTest00"
HASH=$(echo -n "$RAW_KEY" | sha256sum | cut -d' ' -f1)

psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -c "SELECT ak.name, ak.status, t.legal_name
      FROM api_keys ak
      JOIN tenants t ON t.tenant_id = ak.tenant_id
      WHERE ak.key_hash = '$HASH';"
```

### Actualizar expiración de una API Key

```sql
UPDATE api_keys
SET expires_at = '2027-12-31 23:59:59+00'
WHERE api_key_id = 'UUID_DE_LA_KEY';
```

---

## Consulta de documentos

> **Importante**: Los documentos están en el esquema del tenant. Siempre ejecutar `SET search_path` antes.

### Cambiar al esquema de un tenant

```sql
SET search_path TO tenant_demo, public;
```

### Listar documentos recientes

```sql
SELECT
    document_id,
    document_type,
    establishment || '-' || issue_point || '-' || sequence_number AS serie,
    access_key,
    status,
    recipient_name,
    total_amount,
    issue_date,
    created_at
FROM documents
ORDER BY created_at DESC
LIMIT 20;
```

### Contar documentos por estado

```sql
SELECT
    status,
    COUNT(*) AS total
FROM documents
GROUP BY status
ORDER BY total DESC;
```

### Buscar documento por clave de acceso

```sql
SELECT *
FROM documents
WHERE access_key = '0404202601179001691900110010010000000421234567817';
```

### Buscar documentos por receptor

```sql
SELECT
    document_id, status,
    establishment || '-' || issue_point || '-' || sequence_number AS serie,
    recipient_id, recipient_name, total_amount, issue_date
FROM documents
WHERE recipient_id = '1790012345001'
ORDER BY issue_date DESC;
```

### Documentos por rango de fecha

```sql
SELECT
    document_id, status,
    establishment || '-' || issue_point || '-' || sequence_number AS serie,
    recipient_name, total_amount, issue_date
FROM documents
WHERE issue_date BETWEEN '2026-04-01' AND '2026-04-30'
ORDER BY issue_date DESC;
```

### Documentos en estado de error

```sql
SELECT
    document_id,
    status,
    establishment || '-' || issue_point || '-' || sequence_number AS serie,
    last_error_code,
    last_error_message,
    retry_count,
    next_retry_at,
    updated_at
FROM documents
WHERE status IN ('REJECTED', 'FAILED', 'RETRY')
ORDER BY updated_at DESC;
```

### Documentos con mensajes del SRI

```sql
SELECT
    document_id,
    status,
    sri_messages,
    last_error_code,
    last_error_message
FROM documents
WHERE sri_messages IS NOT NULL
  AND sri_messages != '[]'::jsonb
ORDER BY updated_at DESC
LIMIT 10;
```

### Resumen de facturación del mes

```sql
SELECT
    document_type,
    COUNT(*) AS total_docs,
    SUM(total_amount) AS monto_total,
    COUNT(*) FILTER (WHERE status = 'AUTHORIZED') AS autorizados,
    COUNT(*) FILTER (WHERE status = 'NOTIFIED') AS notificados,
    COUNT(*) FILTER (WHERE status = 'REJECTED') AS rechazados,
    COUNT(*) FILTER (WHERE status = 'FAILED') AS fallidos,
    COUNT(*) FILTER (WHERE status = 'VOIDED') AS anulados
FROM documents
WHERE issue_date >= date_trunc('month', CURRENT_DATE)
GROUP BY document_type;
```

---

## Anulación de documentos

### Marcar un documento como VOIDED

> Normalmente se hace vía API (`POST /v1/invoices/:id/void`). Solo usar SQL directamente en casos excepcionales.

```sql
-- Verificar estado actual
SELECT document_id, status, recipient_id_type
FROM documents
WHERE document_id = 'UUID_DEL_DOCUMENTO';

-- Solo anular si está en AUTHORIZED o NOTIFIED
-- y no es consumidor final (recipient_id_type != '07')
UPDATE documents
SET
    status = 'VOIDED',
    voided_at = now(),
    void_reason = 'Anulación manual por DBA - ticket #123',
    updated_at = now(),
    version = version + 1
WHERE document_id = 'UUID_DEL_DOCUMENTO'
  AND status IN ('AUTHORIZED', 'NOTIFIED')
  AND recipient_id_type != '07'
RETURNING document_id, status, voided_at;
```

### Listar documentos anulados

```sql
SELECT
    document_id,
    establishment || '-' || issue_point || '-' || sequence_number AS serie,
    access_key,
    void_reason,
    voided_at,
    recipient_name,
    total_amount
FROM documents
WHERE status = 'VOIDED'
ORDER BY voided_at DESC;
```

---

## Outbox y colas

### Ver eventos pendientes de publicar

```sql
SET search_path TO tenant_demo, public;

SELECT
    outbox_id,
    event_type,
    aggregate_id,
    created_at,
    published,
    published_at
FROM outbox
WHERE published = false
ORDER BY created_at ASC;
```

### Contar eventos por estado

```sql
SELECT
    published,
    COUNT(*) AS total
FROM outbox
GROUP BY published;
```

### Ver eventos recientes publicados

```sql
SELECT
    outbox_id,
    event_type,
    aggregate_id,
    created_at,
    published_at
FROM outbox
WHERE published = true
ORDER BY published_at DESC
LIMIT 20;
```

### Forzar reproceso de un evento (marcar como no publicado)

> **Precaución**: Esto puede causar duplicación de procesamiento. Usar solo cuando un evento se perdió.

```sql
UPDATE outbox
SET published = false, published_at = NULL
WHERE outbox_id = 'UUID_DEL_EVENTO';
```

---

## Webhooks

### Ver entregas de webhook

```sql
SET search_path TO tenant_demo, public;

SELECT
    wd.webhook_delivery_id,
    wd.event_type,
    wd.url,
    wd.response_status,
    wd.duration_ms,
    wd.attempt,
    wd.status,
    wd.created_at
FROM webhook_deliveries wd
ORDER BY wd.created_at DESC
LIMIT 20;
```

### Ver webhooks fallidos

```sql
SELECT
    wd.webhook_delivery_id,
    wd.event_type,
    wd.url,
    wd.response_status,
    wd.response_body,
    wd.attempt,
    wd.max_attempts,
    wd.status,
    wd.created_at
FROM webhook_deliveries wd
WHERE wd.status = 'failed'
ORDER BY wd.created_at DESC;
```

### Ver webhooks pendientes de reintento

```sql
SELECT
    wd.webhook_delivery_id,
    wd.event_type,
    wd.attempt,
    wd.next_attempt_at,
    wd.status
FROM webhook_deliveries wd
WHERE wd.status = 'pending'
  AND wd.next_attempt_at <= now()
ORDER BY wd.next_attempt_at ASC;
```

---

## Monitoreo y diagnóstico

### Documentos procesados hoy (por tenant)

```sql
-- Ejecutar para cada tenant activo
DO $$
DECLARE
    t RECORD;
    cnt INTEGER;
BEGIN
    FOR t IN SELECT schema_name, legal_name FROM tenants WHERE status = 'active'
    LOOP
        EXECUTE format('SELECT COUNT(*) FROM %I.documents WHERE created_at >= CURRENT_DATE', t.schema_name) INTO cnt;
        RAISE NOTICE '% (%): % documentos hoy', t.legal_name, t.schema_name, cnt;
    END LOOP;
END $$;
```

### Documentos atascados (en RETRY por más de 1 hora)

```sql
SET search_path TO tenant_demo, public;

SELECT
    document_id,
    status,
    establishment || '-' || issue_point || '-' || sequence_number AS serie,
    retry_count,
    max_retries,
    next_retry_at,
    last_error_code,
    last_error_message,
    updated_at
FROM documents
WHERE status = 'RETRY'
  AND updated_at < now() - INTERVAL '1 hour'
ORDER BY updated_at ASC;
```

### Tasa de error de las últimas 24 horas

```sql
SET search_path TO tenant_demo, public;

SELECT
    COUNT(*) AS total,
    COUNT(*) FILTER (WHERE status IN ('AUTHORIZED', 'NOTIFIED')) AS exitosos,
    COUNT(*) FILTER (WHERE status IN ('REJECTED', 'FAILED')) AS con_error,
    ROUND(
        COUNT(*) FILTER (WHERE status IN ('REJECTED', 'FAILED'))::NUMERIC
        / NULLIF(COUNT(*), 0) * 100, 2
    ) AS porcentaje_error
FROM documents
WHERE created_at >= now() - INTERVAL '24 hours';
```

### Uso de API keys (última actividad)

```sql
SELECT
    t.legal_name,
    ak.name AS key_name,
    ak.key_prefix,
    ak.status,
    ak.last_used_at,
    now() - ak.last_used_at AS inactivo_hace
FROM api_keys ak
JOIN tenants t ON t.tenant_id = ak.tenant_id
WHERE ak.status = 'active'
ORDER BY ak.last_used_at DESC NULLS LAST;
```

### Certificados próximos a vencer

```sql
SELECT
    ruc,
    legal_name,
    certificate_subject,
    certificate_expiration,
    EXTRACT(DAY FROM certificate_expiration - now())::INT AS dias_restantes
FROM tenants
WHERE certificate_expiration IS NOT NULL
  AND certificate_expiration < now() + INTERVAL '60 days'
ORDER BY certificate_expiration ASC;
```

### Tamaño de esquemas (uso de disco)

```sql
SELECT
    t.schema_name,
    t.legal_name,
    pg_size_pretty(
        SUM(pg_total_relation_size(c.oid))
    ) AS tamaño
FROM tenants t
JOIN pg_namespace n ON n.nspname = t.schema_name
JOIN pg_class c ON c.relnamespace = n.oid AND c.relkind = 'r'
GROUP BY t.schema_name, t.legal_name
ORDER BY SUM(pg_total_relation_size(c.oid)) DESC;
```

### Registro de auditoría

```sql
SET search_path TO tenant_demo, public;

SELECT
    action,
    entity_type,
    entity_id,
    ip_address,
    created_at
FROM audit_log
ORDER BY created_at DESC
LIMIT 20;
```

---

## Particionamiento de documents

La tabla `documents` está particionada por rango mensual sobre `issue_date` (desde V005).

### Verificar que la tabla está particionada

```sql
SET search_path TO tenant_demo;

-- Debe mostrar relkind = 'p' (partitioned)
SELECT c.relkind, c.relname
FROM pg_class c
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE c.relname = 'documents' AND n.nspname = 'tenant_demo';
```

### Listar particiones existentes

```sql
SET search_path TO tenant_demo;

SELECT child.relname AS partition_name,
       pg_get_expr(child.relpartbound, child.oid) AS range
FROM pg_inherits i
JOIN pg_class parent ON i.inhparent = parent.oid
JOIN pg_class child ON i.inhrelid = child.oid
WHERE parent.relname = 'documents'
ORDER BY child.relname;
```

### Crear particiones futuras manualmente

```sql
SET search_path TO tenant_demo;

-- Ejemplo: crear partición para julio 2026
CREATE TABLE documents_2026_07 PARTITION OF documents
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
```

### Crear particiones automáticamente (cron)

```bash
# Crea particiones para los próximos 3 meses en todos los tenants activos
export PGPASSWORD=1234abcd
db/maintenance/create_monthly_partitions.sh 3

# Cron: 1ro de cada mes a las 02:00
# 0 2 1 * * /opt/key49/db/maintenance/create_monthly_partitions.sh 3 >> /var/log/key49/partitions.log 2>&1
```

### Verificar partition pruning

```sql
SET search_path TO tenant_demo;

-- Debe mostrar solo la partición del mes consultar (ej: documents_2026_04)
EXPLAIN SELECT * FROM documents
WHERE issue_date >= '2026-04-01' AND issue_date < '2026-05-01';

-- Con tipo de documento (patrón principal de las APIs)
EXPLAIN SELECT * FROM documents
WHERE document_type = '01'
  AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01'
ORDER BY issue_date DESC;
```

### Archivar particiones antiguas

```sql
SET search_path TO tenant_demo;

-- Desprender la partición (los datos quedan en la tabla independiente)
ALTER TABLE documents DETACH PARTITION documents_2025_01;

-- Exportar y eliminar
pg_dump ... --table=tenant_demo.documents_2025_01 -f archive_2025_01.sql
DROP TABLE documents_2025_01;
```

### Migrar tenant existente a particionamiento

```bash
# Ejecutar V005 en un esquema de tenant existente
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -c "SET search_path TO tenant_demo;" \
  -f db/migrations/tenant/V005__partition_documents.sql
```

> **Importante**: V005 reescribe la tabla completa. Ejecutar en ventana de mantenimiento.
> Respalde el esquema antes de ejecutar.

---

## Mantenimiento automatizado

Los scripts de mantenimiento están en `db/maintenance/`.

### Scripts disponibles

| Script                         | Propósito                             | Frecuencia recomendada   |
| ------------------------------ | ------------------------------------- | ------------------------ |
| `vacuum_analyze.sh`            | VACUUM ANALYZE en todos los esquemas  | Diario (03:00)           |
| `tune_autovacuum.sh`           | Ajustar parámetros autovacuum         | Una vez + nuevos tenants |
| `monitor_bloat.sh`             | Reportar dead tuples y bloat          | Semanal (dom 04:00)      |
| `reindex_concurrently.sh`      | Reconstruir índices sin downtime      | Mensual (1er dom)        |
| `create_monthly_partitions.sh` | Crear particiones futuras (documents) | Mensual (1ro 02:00)      |

### VACUUM ANALYZE

Ejecuta `VACUUM ANALYZE` en todas las tablas de todos los esquemas activos (incluyendo `public`).

```bash
# Todos los esquemas
export PGPASSWORD=1234abcd
db/maintenance/vacuum_analyze.sh

# Un solo esquema
db/maintenance/vacuum_analyze.sh --schema tenant_demo

# VACUUM FULL (requiere downtime — adquiere lock exclusivo)
db/maintenance/vacuum_analyze.sh --full
```

Cron (diario a las 03:00):

```cron
0 3 * * * /opt/key49/db/maintenance/vacuum_analyze.sh >> /var/log/key49/vacuum.log 2>&1
```

### Tuning de autovacuum para documents

La tabla `documents` tiene alta tasa de escritura (INSERT + múltiples UPDATE por estado).
Configuramos autovacuum más agresivo:

| Parámetro                         | Default | Key49     | Efecto                                    |
| --------------------------------- | ------- | --------- | ----------------------------------------- |
| `autovacuum_vacuum_scale_factor`  | 0.20    | **0.05**  | Vacuum al 5% de dead tuples (no al 20%)   |
| `autovacuum_analyze_scale_factor` | 0.10    | **0.05**  | Re-analizar estadísticas al 5% de cambios |
| `autovacuum_vacuum_cost_delay`    | 2 ms    | **10 ms** | Throttle leve para no competir con I/O    |

```bash
# Aplicar a todos los tenants activos
export PGPASSWORD=1234abcd
db/maintenance/tune_autovacuum.sh

# Un solo tenant
db/maintenance/tune_autovacuum.sh tenant_demo
```

> **Importante**: Ejecutar al crear un nuevo tenant y después de aplicar V005 (particionamiento).
> Los parámetros se aplican por tabla — las particiones heredan del padre.

Verificar configuración actual:

```sql
SELECT n.nspname AS schema, c.relname AS table_name,
       array_to_string(c.reloptions, ', ') AS options
FROM pg_class c
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE c.relname LIKE 'documents%'
  AND c.reloptions IS NOT NULL
ORDER BY n.nspname, c.relname;
```

### Monitoreo de bloat y dead tuples

Genera un reporte de dead tuples, tamaños de tabla/índice, y actividad de autovacuum.

```bash
# Todas los esquemas
export PGPASSWORD=1234abcd
db/maintenance/monitor_bloat.sh

# Solo tablas con > 10000 dead tuples
db/maintenance/monitor_bloat.sh --threshold 10000

# Un esquema específico
db/maintenance/monitor_bloat.sh --schema tenant_demo
```

El reporte incluye 4 secciones:

1. **Dead Tuples Report** — dead tuples por tabla, con `last_vacuum` / `last_autovacuum`
2. **Estimated Table Bloat** — tamaño tabla vs. tamaño índices
3. **Autovacuum Activity (24h)** — conteo de vacuum/analyze automáticos recientes
4. **Tables Needing Vacuum** — tablas donde dead > 5% de live tuples

Cron (semanal, domingo 04:00):

```cron
0 4 * * 0 /opt/key49/db/maintenance/monitor_bloat.sh >> /var/log/key49/bloat.log 2>&1
```

#### Consulta manual de dead tuples

```sql
SELECT
    schemaname AS schema,
    relname AS tabla,
    n_live_tup AS vivas,
    n_dead_tup AS muertas,
    CASE WHEN n_live_tup > 0
        THEN ROUND(n_dead_tup::NUMERIC / n_live_tup * 100, 2)
        ELSE 0
    END AS pct_muertas,
    last_autovacuum
FROM pg_stat_user_tables
WHERE schemaname LIKE 'tenant_%'
  AND n_dead_tup > n_live_tup * 0.05
ORDER BY n_dead_tup DESC;
```

### Reindexación sin downtime

`REINDEX CONCURRENTLY` reconstruye índices sin bloquear lecturas ni escrituras.

```bash
# Todos los índices de todos los esquemas
export PGPASSWORD=1234abcd
db/maintenance/reindex_concurrently.sh

# Solo un esquema
db/maintenance/reindex_concurrently.sh --schema tenant_demo

# Solo índices de la tabla documents
db/maintenance/reindex_concurrently.sh --table documents
```

Cron (1er domingo de cada mes a las 04:00):

```cron
0 4 1-7 * 0 /opt/key49/db/maintenance/reindex_concurrently.sh >> /var/log/key49/reindex.log 2>&1
```

> **Nota**: `REINDEX CONCURRENTLY` no puede ejecutarse dentro de una transacción.
> El script ejecuta cada `REINDEX` como statement individual.

### Crontab recomendado (producción)

```cron
# Key49 — Mantenimiento PostgreSQL
# Particiones: 1ro de cada mes a las 02:00
0 2 1 * * /opt/key49/db/maintenance/create_monthly_partitions.sh 3 >> /var/log/key49/partitions.log 2>&1

# VACUUM ANALYZE: diario a las 03:00
0 3 * * * /opt/key49/db/maintenance/vacuum_analyze.sh >> /var/log/key49/vacuum.log 2>&1

# Monitor bloat: domingo a las 04:00
0 4 * * 0 /opt/key49/db/maintenance/monitor_bloat.sh >> /var/log/key49/bloat.log 2>&1

# Reindex: 1er domingo de cada mes a las 05:00
0 5 1-7 * 0 /opt/key49/db/maintenance/reindex_concurrently.sh >> /var/log/key49/reindex.log 2>&1
```

Variables de entorno requeridas por los scripts:

```bash
export KEY49_DB_HOST=localhost
export KEY49_DB_PORT=5433
export KEY49_DB_NAME=key49
export KEY49_DB_USER=postgres
export PGPASSWORD=<password_seguro>
```

---

## Mantenimiento manual

### Limpieza del outbox (eventos publicados con más de 7 días)

> El job automático `OutboxCleanup` ejecuta esto diariamente a las 02:00 ECT. Usar manualmente solo si es necesario.

```sql
SET search_path TO tenant_demo, public;

DELETE FROM outbox
WHERE published = true
  AND published_at < now() - INTERVAL '7 days';
```

### Limpieza de webhook_deliveries antiguas

```sql
SET search_path TO tenant_demo, public;

-- Eliminar entregas exitosas de más de 90 días
DELETE FROM webhook_deliveries
WHERE status = 'delivered'
  AND created_at < now() - INTERVAL '90 days';
```

### Verificar integridad de las tablas de un tenant

```sql
SET search_path TO tenant_demo;

-- Verificar que existen todas las tablas requeridas
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'tenant_demo'
ORDER BY table_name;

-- Resultado esperado: audit_log, documents, outbox, webhook_deliveries
```

### Backup de un esquema de tenant

```bash
# Backup del esquema público
pg_dump -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  --schema=public --no-owner --no-acl \
  -f backup_public_$(date +%Y%m%d).sql

# Backup de un tenant específico
pg_dump -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  --schema=tenant_demo --no-owner --no-acl \
  -f backup_tenant_demo_$(date +%Y%m%d).sql

# Backup completo
pg_dump -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  --no-owner --no-acl \
  -f backup_key49_$(date +%Y%m%d).sql
```

### Restore de un esquema

```bash
# Restore (asegurarse de que el esquema no exista o usar --clean)
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -f backup_tenant_demo_20260406.sql
```

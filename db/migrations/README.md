# Scripts de Migración SQL — Key49

## Ejecución

**IMPORTANTE**: NO hay migraciones automáticas. Todos los scripts se ejecutan manualmente por el DBA.

## Estructura

```
db/migrations/
├── public/                     # Esquema public (ejecutar una sola vez)
│   ├── V001__create_tenants.sql
│   └── V002__create_api_keys.sql
├── tenant/                     # Esquema de cada tenant (ejecutar en cada esquema)
│   ├── V001__create_documents.sql
│   ├── V002__create_outbox.sql
│   ├── V003__create_webhook_deliveries.sql
│   └── V004__create_audit_log.sql
└── README.md
```

## Crear base de datos y esquema público

```sql
-- 1. Crear base de datos
CREATE DATABASE key49;

-- 2. Conectar a la base de datos y ejecutar scripts del esquema público
\c key49
\i db/migrations/public/V001__create_tenants.sql
\i db/migrations/public/V002__create_api_keys.sql
```

## Provisionar un nuevo tenant

```sql
-- 1. Registrar el tenant en el esquema público
INSERT INTO public.tenants (ruc, legal_name, main_address, schema_name)
VALUES ('0991234567001', 'Empresa Ejemplo S.A.', 'Guayaquil', 'tenant_abc123');

-- 2. Crear el esquema del tenant
CREATE SCHEMA tenant_abc123;

-- 3. Ejecutar scripts de tablas dentro del esquema
SET search_path TO tenant_abc123;
\i db/migrations/tenant/V001__create_documents.sql
\i db/migrations/tenant/V002__create_outbox.sql
\i db/migrations/tenant/V003__create_webhook_deliveries.sql
\i db/migrations/tenant/V004__create_audit_log.sql

-- 4. Restaurar search_path
SET search_path TO public;
```

## Aplicar migraciones a todos los esquemas de tenant existentes

```sql
-- Listar todos los esquemas de tenant activos
SELECT schema_name FROM public.tenants WHERE status = 'active';

-- Ejecutar un script en todos los esquemas
DO $$
DECLARE
    s TEXT;
BEGIN
    FOR s IN SELECT schema_name FROM public.tenants WHERE status = 'active'
    LOOP
        EXECUTE format('SET search_path TO %I', s);
        -- Ejecutar ALTER TABLE u otro DDL aquí
    END LOOP;
    SET search_path TO public;
END $$;
```

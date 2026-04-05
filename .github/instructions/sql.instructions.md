---
applyTo: "**/*.sql"
---
# Reglas SQL — Key49

## Idioma
- TODO en inglés: tablas, columnas, constraints, comentarios

## Convenciones
- Tablas: plural, snake_case (`documents`, `webhook_deliveries`)
- PKs: `{table_singular}_id` UUID con `DEFAULT gen_random_uuid()`
- FKs: mismo nombre que PK referenciada
- Índices: `idx_{table}_{column}`
- UNIQUE: `uq_{table}_{column}`
- CHECK: `chk_{table}_{condition}`

## Tipos
- Identificadores: `UUID`
- Montos: `NUMERIC(14,2)`
- Cantidades: `NUMERIC(14,6)`
- Fechas: `DATE`
- Timestamps: `TIMESTAMP WITH TIME ZONE`
- JSON: `JSONB`

## Multi-tenancy
- Esquema `public`: tenants, api_keys
- Esquema `tenant_{uuid_short}`: documents, outbox, webhook_deliveries, audit_log
- Tablas del tenant NO tienen columna `tenant_id`
- NO hay migraciones automáticas — ejecución manual por DBA

## Schema completo
- Ver `DATABASE.md` para definiciones de tablas y constraints

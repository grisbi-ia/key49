# Esquema de Base de Datos — Key49

## Motor: PostgreSQL 16

## Convenciones de Nombrado

- **Tablas**: inglés, plural, snake_case (`documents`, `webhook_deliveries`)
- **Columnas**: inglés, snake_case (`issue_date`, `access_key`, `status`)
- **Claves primarias**: `{table_singular}_id` (`tenant_id`, `document_id`)
- **Claves foráneas**: mismo nombre que la PK referenciada (`tenant_id` → `tenants.tenant_id`)
- **Índices**: `idx_{table}_{column}` (`idx_documents_status`)
- **Constraints**: `uq_{table}_{column}`, `chk_{table}_{condition}`

## Estrategia Multi-Tenant: Schema-per-Tenant

- **Esquema `public`**: contiene tablas de administración (`tenants`, `api_keys`). Accesibles por la aplicación sin filtro de tenant.
- **Esquema `tenant_{uuid_short}`**: un esquema por tenant con todas las tablas de negocio (`documents`, `outbox`, `webhook_deliveries`, `audit_log`).
- Las tablas dentro del esquema del tenant **NO tienen columna `tenant_id`** — el aislamiento lo garantiza el esquema de PostgreSQL.
- Al inicio de cada request se ejecuta `SET search_path TO 'tenant_{uuid_short}', public;`
- **NO hay migraciones automáticas**: todos los scripts SQL se ejecutan manualmente por el DBA.
- **Crear un tenant = solo INSERT** en `public.tenants` con el campo `schema_name`. El esquema y sus tablas se crean manualmente.

## Esquema Público (`public`)

Tablas de administración compartidas.

### Tabla: tenants

Registro de cada cliente de la API (empresa emisora).

```sql
CREATE TABLE tenants (
    tenant_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ruc                 VARCHAR(13) NOT NULL,
    legal_name          VARCHAR(300) NOT NULL,
    trade_name          VARCHAR(300),
    main_address        VARCHAR(300) NOT NULL,
    required_accounting BOOLEAN NOT NULL DEFAULT false,
    special_taxpayer    VARCHAR(20),
    micro_enterprise_regime BOOLEAN NOT NULL DEFAULT false,
    withholding_agent   VARCHAR(10),
    environment         VARCHAR(10) NOT NULL DEFAULT 'test', -- test | production
    emission_type       SMALLINT NOT NULL DEFAULT 1,         -- 1 = normal
    logo_url            VARCHAR(500),

    -- Digital certificate (encrypted AES-256-GCM)
    certificate_p12     BYTEA,                               -- encrypted .p12
    certificate_password_enc BYTEA,                          -- encrypted password
    certificate_subject VARCHAR(500),                        -- certificate CN
    certificate_expiration TIMESTAMP WITH TIME ZONE,         -- expiration date
    certificate_serial  VARCHAR(100),                        -- serial number

    -- Configuration
    webhook_url         VARCHAR(500),
    webhook_secret      VARCHAR(100),                        -- HMAC secret for webhook signing
    rate_limit_rpm      INT NOT NULL DEFAULT 100,            -- requests per minute
    email_sender_name   VARCHAR(200),
    reply_email         VARCHAR(200),

    -- Tenant PostgreSQL schema
    schema_name         VARCHAR(63) NOT NULL,                -- schema name (e.g. 'tenant_abc123')

    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'active', -- active | suspended | pending
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT uq_tenants_ruc UNIQUE (ruc),
    CONSTRAINT uq_tenants_schema UNIQUE (schema_name)
);

CREATE INDEX idx_tenants_status ON tenants(status);
```

### Tabla: api_keys

Claves de API para autenticación de tenants.

```sql
CREATE TABLE api_keys (
    api_key_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    key_prefix  VARCHAR(8) NOT NULL,        -- first 8 chars (for visual identification)
    key_hash    VARCHAR(128) NOT NULL,      -- SHA-256 of the full API key
    name        VARCHAR(100) NOT NULL,      -- descriptive name (e.g. "Production ERP")
    permissions VARCHAR(500) NOT NULL DEFAULT '*', -- future: granular permissions
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE,
    status      VARCHAR(20) NOT NULL DEFAULT 'active', -- active | revoked
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash)
);

CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_prefix ON api_keys(key_prefix);
```

## Esquema del Tenant (`tenant_{uuid_short}`)

Todas las tablas a partir de aquí viven dentro del esquema dedicado de cada tenant. No tienen columna `tenant_id`.

> **Nota sobre secuenciales y detalle**: Key49 NO gestiona secuenciales (`sequence_number`) ni almacena detalle de ítems/pagos en tablas separadas. El secuencial lo envía el cliente en su request. Los ítems y pagos se preservan en el `request_payload` (JSON) o `original_xml` (XML raw) y en los XML almacenados en MinIO.

---

### Tabla: documents

Tabla central de comprobantes electrónicos.

```sql
CREATE TABLE documents (
    document_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Document identification
    document_type       VARCHAR(2) NOT NULL,     -- 01, 03, 04, 05, 06, 07
    establishment       VARCHAR(3) NOT NULL,
    issue_point         VARCHAR(3) NOT NULL,
    sequence_number     VARCHAR(9) NOT NULL,
    access_key          VARCHAR(49),             -- generated by system
    authorization_number VARCHAR(49),            -- returned by SRI

    -- Request origin (see ADR-006)
    request_origin      VARCHAR(10) NOT NULL DEFAULT 'JSON', -- JSON | XML_RAW

    -- Recipient data
    recipient_id_type   VARCHAR(2) NOT NULL,      -- 04=RUC, 05=cédula, 06=passport, 07=final consumer
    recipient_id        VARCHAR(20) NOT NULL,
    recipient_name      VARCHAR(300) NOT NULL,
    recipient_email     VARCHAR(500),            -- multiple separated by ;
    recipient_address   VARCHAR(300),
    recipient_phone     VARCHAR(50),

    -- Amounts
    subtotal_before_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_vat_0      NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_vat_12     NUMERIC(14,2) NOT NULL DEFAULT 0,  -- or current rate
    subtotal_vat_15     NUMERIC(14,2) NOT NULL DEFAULT 0,  -- 15% rate if applicable
    subtotal_non_taxable NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal_exempt     NUMERIC(14,2) NOT NULL DEFAULT 0,
    vat_amount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    ice_amount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    tip                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    currency            VARCHAR(15) NOT NULL DEFAULT 'DOLAR',

    -- Dates
    issue_date          DATE NOT NULL,
    authorization_date  TIMESTAMP WITH TIME ZONE,
    sri_submission_date TIMESTAMP WITH TIME ZONE,

    -- Pipeline status
    status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    -- CREATED → SIGNED → SENT → RECEIVED → AUTHORIZED → NOTIFIED
    -- Error states: REJECTED, FAILED
    -- Intermediate states: RETRY
    -- Post-authorization: VOIDED (anulado localmente)

    -- Processing
    retry_count         SMALLINT NOT NULL DEFAULT 0,
    max_retries         SMALLINT NOT NULL DEFAULT 6,
    next_retry_at       TIMESTAMP WITH TIME ZONE,
    last_error_code     VARCHAR(10),
    last_error_message  TEXT,
    sri_messages        JSONB,                   -- array of SRI messages

    -- Storage (MinIO paths)
    unsigned_xml_path   VARCHAR(500),            -- unsigned XML
    signed_xml_path     VARCHAR(500),            -- signed XML
    authorized_xml_path VARCHAR(500),            -- authorized XML
    ride_path           VARCHAR(500),            -- RIDE PDF

    -- Original request data
    request_payload     JSONB,                   -- original payload (JSON channel only)
    original_xml        TEXT,                     -- client's original XML (XML_RAW channel only)
    request_ip          VARCHAR(45),
    idempotency_key     VARCHAR(50),             -- client idempotency key

    -- Email delivery tracking
    email_sent_at       TIMESTAMP WITH TIME ZONE,
    email_status        VARCHAR(20),             -- pending, sent, failed, skipped
    email_error         VARCHAR(500),            -- error message if failed

    -- Void/cancellation (local only, SRI anulation done by taxpayer)
    voided_at           TIMESTAMP WITH TIME ZONE,
    void_reason         VARCHAR(500),

    -- Metadata
    version             INT NOT NULL DEFAULT 1,  -- optimistic locking
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT uq_documents_access_key UNIQUE (access_key),
    CONSTRAINT uq_documents_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_documents_number UNIQUE (document_type, establishment, issue_point, sequence_number),
    CONSTRAINT chk_documents_status CHECK (
        status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'AUTHORIZED', 'NOTIFIED', 'REJECTED', 'FAILED', 'RETRY', 'VOIDED')
    ),
    CONSTRAINT chk_documents_origin CHECK (
        (request_origin = 'JSON' AND request_payload IS NOT NULL) OR
        (request_origin = 'XML_RAW' AND original_xml IS NOT NULL)
    )
);

-- Main indexes
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_issue_date ON documents(issue_date DESC);
CREATE INDEX idx_documents_recipient ON documents(recipient_id);
CREATE INDEX idx_documents_retry ON documents(status, next_retry_at) WHERE status = 'RETRY';
CREATE INDEX idx_documents_access_key ON documents(access_key) WHERE access_key IS NOT NULL;
CREATE INDEX idx_documents_type_date ON documents(document_type, issue_date DESC);
CREATE INDEX idx_documents_origin ON documents(request_origin);
```

### Tabla: outbox

Tabla de outbox para garantía de entrega de eventos.

```sql
CREATE TABLE outbox (
    outbox_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50) NOT NULL,     -- 'document'
    aggregate_id    UUID NOT NULL,            -- document.document_id
    event_type      VARCHAR(50) NOT NULL,     -- 'DOCUMENT_SIGNED', 'DOCUMENT_AUTHORIZED', etc.
    payload         JSONB NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT false,
    published_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT idx_outbox_pending CHECK (true) -- dummy for partial index below
);

CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published = false;
```

### Tabla: webhook_deliveries

Log de entregas de webhooks a integradores.

```sql
CREATE TABLE webhook_deliveries (
    webhook_delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(document_id),

    event_type      VARCHAR(50) NOT NULL,     -- 'document.authorized', 'document.rejected', etc.
    url             VARCHAR(500) NOT NULL,
    request_body    JSONB NOT NULL,
    response_status INT,
    response_body   TEXT,
    duration_ms     INT,

    attempt         SMALLINT NOT NULL DEFAULT 1,
    max_attempts    SMALLINT NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMP WITH TIME ZONE,

    status          VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending, delivered, failed
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_pending ON webhook_deliveries(status, next_attempt_at) WHERE status = 'pending';
```

### Tabla: audit_log

Log de auditoría para compliance.

```sql
CREATE TABLE audit_log (
    audit_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(100),
    action      VARCHAR(50) NOT NULL,       -- 'document.created', 'tenant.cert_updated', etc.
    entity_type VARCHAR(50) NOT NULL,
    entity_id   UUID NOT NULL,
    old_values  JSONB,
    new_values  JSONB,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
```

## Scripts de Migración (Ejecución Manual)

**IMPORTANTE**: NO hay migraciones automáticas. La aplicación NO ejecuta Flyway ni Liquibase al arrancar. Todos los scripts SQL se ejecutan manualmente por el DBA.

### Estructura de archivos

Los scripts de referencia se mantienen en `db/migrations/` con el siguiente patrón:

```
db/migrations/
├── public/                     # Scripts para esquema public
│   ├── V001__create_tenants.sql
│   └── V002__create_api_keys.sql
├── tenant/                     # Scripts para esquemas de tenant (se ejecutan en cada esquema)
│   ├── V001__create_documents.sql
│   ├── V002__create_outbox.sql
│   ├── V003__create_webhook_deliveries.sql
│   └── V004__create_audit_log.sql
└── README.md                   # Instrucciones de ejecución para el DBA
```

> **Nota**: Los catálogos SRI (tipos de impuesto, formas de pago, tipos de identificación) NO se almacenan en tablas. Se modelan como enums Java en `key49-core` (ver CONVENTIONS.md). Son datos estables que solo cambian con actualizaciones de ficha técnica del SRI.

### Provisionar un nuevo tenant (manual)

```sql
-- 1. Register the tenant in the public schema
INSERT INTO public.tenants (ruc, legal_name, main_address, schema_name)
VALUES ('0991234567001', 'Empresa Ejemplo S.A.', 'Guayaquil', 'tenant_abc123');

-- 2. Crear el esquema del tenant (ejecutado por DBA)
CREATE SCHEMA tenant_abc123;

-- 3. Ejecutar los scripts de tabla dentro del esquema
SET search_path TO tenant_abc123;
-- Ejecutar cada script de db/migrations/tenant/ en orden
\i db/migrations/tenant/V001__create_documents.sql
\i db/migrations/tenant/V002__create_outbox.sql
\i db/migrations/tenant/V003__create_webhook_deliveries.sql
\i db/migrations/tenant/V004__create_audit_log.sql
```

### Aplicar migraciones a esquemas existentes

Cuando se agrega una nueva columna o tabla, el DBA debe ejecutar el ALTER/CREATE en cada esquema de tenant:

```sql
-- List all tenant schemas
SELECT schema_name FROM public.tenants WHERE status = 'active';

-- Run migration script on each schema
DO $$
DECLARE
    s TEXT;
BEGIN
    FOR s IN SELECT schema_name FROM public.tenants WHERE status = 'active'
    LOOP
        EXECUTE format('SET search_path TO %I', s);
        -- Run ALTER or CREATE here
    END LOOP;
END $$;
```

# Guía de Despliegue — Key49

Guía paso a paso para desplegar Key49 desde cero en un ambiente de pruebas.

---

## Índice

1. [Prerrequisitos](#prerrequisitos)
2. [Infraestructura](#infraestructura)
3. [Base de datos](#base-de-datos)
4. [MinIO (almacenamiento)](#minio-almacenamiento)
5. [Variables de entorno](#variables-de-entorno)
6. [Crear un tenant](#crear-un-tenant)
7. [Generar una API Key](#generar-una-api-key)
8. [Arrancar la aplicación](#arrancar-la-aplicación)
9. [Verificar el despliegue](#verificar-el-despliegue)
10. [Troubleshooting](#troubleshooting)

---

## Prerrequisitos

| Componente | Versión mínima | Notas                                        |
| ---------- | -------------- | -------------------------------------------- |
| Java (JDK) | 25             | OpenJDK 25 o superior                        |
| Maven      | 3.9+           | Para compilar el proyecto                    |
| PostgreSQL | 16             | Con extensión `pgcrypto` (gen_random_uuid)   |
| Redis      | 7              | Para rate limiting, sesiones y caché         |
| RabbitMQ   | 3.13           | Con management plugin habilitado             |
| MinIO      | latest         | Compatible S3 para almacenamiento de XML/PDF |
| Docker     | 24+            | Opcional, para RabbitMQ y MinIO              |

---

## Infraestructura

### Opción A: Docker Compose (RabbitMQ + MinIO)

El archivo `docker-compose.yml` incluye RabbitMQ y MinIO. PostgreSQL y Redis se instalan de forma nativa o en contenedores separados.

```bash
# Iniciar RabbitMQ y MinIO
docker compose up -d

# Verificar que están corriendo
docker compose ps
```

Servicios expuestos:

| Servicio        | Puerto | Consola Web            | Credenciales            |
| --------------- | ------ | ---------------------- | ----------------------- |
| RabbitMQ        | 5672   | http://localhost:15672 | guest / guest           |
| MinIO (API)     | 9000   | —                      | minioadmin / minioadmin |
| MinIO (Console) | 9001   | http://localhost:9001  | minioadmin / minioadmin |

### Opción B: PostgreSQL nativo

```bash
# Instalar PostgreSQL 16 (Ubuntu/Debian)
sudo apt install postgresql-16

# Crear base de datos
sudo -u postgres psql <<SQL
CREATE DATABASE key49;
SQL

# Verificar acceso
psql -h localhost -p 5433 -U postgres -d key49 -c "SELECT version();"
```

### Opción C: Redis nativo

```bash
# Instalar Redis 7 (Ubuntu/Debian)
sudo apt install redis-server

# Verificar
redis-cli ping
# Respuesta esperada: PONG
```

---

## Base de datos

### 1. Crear las tablas del esquema público

Los scripts SQL están en `db/migrations/public/`. Se ejecutan manualmente (Key49 NO usa migraciones automáticas).

```bash
# Variables de conexión
DB_HOST=localhost
DB_PORT=5433
DB_NAME=key49
DB_USER=postgres
export PGPASSWORD=1234abcd

# Ejecutar scripts del esquema público
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -v ON_ERROR_STOP=1 \
  -f db/migrations/public/V001__create_tenants.sql

psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -v ON_ERROR_STOP=1 \
  -f db/migrations/public/V002__create_api_keys.sql
```

### 2. Verificar las tablas

```bash
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -c "\dt public.*"
```

Resultado esperado:

```
          List of relations
 Schema |   Name   | Type  |  Owner
--------+----------+-------+----------
 public | api_keys | table | postgres
 public | tenants  | table | postgres
```

---

## MinIO (almacenamiento)

### Crear el bucket

Con `mc` (MinIO Client):

```bash
# Configurar alias
mc alias set key49 http://localhost:9000 minioadmin minioadmin --api s3v4

# Crear bucket
mc mb key49/key49-documents

# Verificar
mc ls key49/
```

Alternativamente, desde el contenedor Docker:

```bash
docker exec key49-minio mc mb local/key49-documents
```

O desde la consola web de MinIO en `http://localhost:9001`.

---

## Variables de entorno

### Desarrollo local

Para desarrollo, los valores por defecto en `application.properties` son suficientes. Solo se necesita configurar la conexión a PostgreSQL si difiere del default.

| Variable                              | Default                             | Descripción                            |
| ------------------------------------- | ----------------------------------- | -------------------------------------- |
| `KEY49_DB_USER`                       | `postgres`                          | Usuario PostgreSQL                     |
| `KEY49_DB_PASSWORD`                   | `1234abcd`                          | Contraseña PostgreSQL                  |
| `KEY49_DB_REACTIVE_URL`               | `postgresql://localhost:5433/key49` | URL de conexión reactiva               |
| `KEY49_REDIS_URL`                     | `redis://localhost:6379`            | URL de Redis                           |
| `KEY49_RABBITMQ_HOST`                 | `localhost`                         | Host de RabbitMQ                       |
| `KEY49_RABBITMQ_PORT`                 | `5672`                              | Puerto de RabbitMQ                     |
| `KEY49_RABBITMQ_USER`                 | `guest`                             | Usuario de RabbitMQ                    |
| `KEY49_RABBITMQ_PASSWORD`             | `guest`                             | Contraseña de RabbitMQ                 |
| `KEY49_RABBITMQ_PREFETCH_SIGN`        | `10`                                | Prefetch del consumer de firma         |
| `KEY49_RABBITMQ_PREFETCH_SEND`        | `5`                                 | Prefetch del consumer de envío SRI     |
| `KEY49_RABBITMQ_PREFETCH_AUTHORIZE`   | `5`                                 | Prefetch del consumer de autorización  |
| `KEY49_RABBITMQ_PREFETCH_NOTIFY`      | `10`                                | Prefetch del consumer de notificación  |
| `KEY49_RABBITMQ_PREFETCH_DLQ`         | `5`                                 | Prefetch del consumer DLQ              |
| `KEY49_STORAGE_ENDPOINT`              | `http://localhost:9000`             | Endpoint de MinIO/S3                   |
| `KEY49_STORAGE_ACCESS_KEY`            | `minioadmin`                        | Access key de MinIO                    |
| `KEY49_STORAGE_SECRET_KEY`            | `minioadmin`                        | Secret key de MinIO                    |
| `KEY49_STORAGE_BUCKET`                | `key49-documents`                   | Nombre del bucket                      |
| `KEY49_STORAGE_REGION`                | `us-east-1`                         | Región S3                              |
| `KEY49_TIMEZONE`                      | `America/Guayaquil`                 | Zona horaria (UTC-5)                   |
| `KEY49_SRI_ENVIRONMENT`               | `test`                              | Ambiente SRI: `test` o `production`    |
| `KEY49_SRI_RECEPTION_TIMEOUT_MS`      | `3000`                              | Timeout de recepción SOAP (ms)         |
| `KEY49_SRI_AUTHORIZATION_TIMEOUT_MS`  | `5000`                              | Timeout de autorización SOAP (ms)      |
| `KEY49_SRI_MAX_RETRIES`               | `6`                                 | Máximo de reintentos SRI               |
| `KEY49_EMAIL_FROM`                    | `facturacion@key49.ec`              | Email remitente                        |
| `KEY49_EMAIL_ENABLED`                 | `true`                              | Habilitar envío de emails              |
| `KEY49_SMTP_HOST`                     | `localhost`                         | Servidor SMTP                          |
| `KEY49_SMTP_PORT`                     | `1025`                              | Puerto SMTP                            |
| `KEY49_SMTP_USER`                     | (vacío)                             | Usuario SMTP                           |
| `KEY49_SMTP_PASSWORD`                 | (vacío)                             | Contraseña SMTP                        |
| `KEY49_SMTP_START_TLS`                | `DISABLED`                          | StartTLS para SMTP                     |
| `KEY49_SMTP_SSL`                      | `false`                             | SSL para SMTP                          |
| `KEY49_WEBHOOK_ENABLED`               | `true`                              | Habilitar webhooks                     |
| `KEY49_WEBHOOK_CONNECT_TIMEOUT_MS`    | `5000`                              | Timeout de conexión webhook (ms)       |
| `KEY49_WEBHOOK_READ_TIMEOUT_MS`       | `10000`                             | Timeout de lectura webhook (ms)        |
| `KEY49_OUTBOX_POLL_INTERVAL`          | `500ms`                             | Intervalo del outbox poller            |
| `KEY49_OUTBOX_BATCH_SIZE`             | `50`                                | Tamaño del batch del outbox            |
| `KEY49_RETRY_POLL_INTERVAL`           | `5s`                                | Intervalo del retry poller             |
| `KEY49_API_KEY_CACHE_TTL_SECONDS`     | `300`                               | TTL del caché de API keys en Redis (s) |
| `KEY49_DB_POOL_MIN`                   | `5`                                 | Conexiones mínimas del pool Agroal     |
| `KEY49_DB_POOL_MAX`                   | `50`                                | Conexiones máximas del pool Agroal     |
| `KEY49_DB_POOL_ACQUISITION_TIMEOUT`   | `5S`                                | Timeout para obtener conexión          |
| `KEY49_DB_POOL_IDLE_REMOVAL_INTERVAL` | `2M`                                | Intervalo de limpieza de ociosas       |
| `KEY49_DB_POOL_MAX_LIFETIME`          | `30M`                               | Vida máxima de una conexión            |
| `KEY49_THREAD_POOL_MAX`               | `50`                                | Platform threads máximos (fallback)    |

### Producción

Variables adicionales para producción:

| Variable                     | Descripción                                      |
| ---------------------------- | ------------------------------------------------ |
| `KEY49_SRI_ENVIRONMENT`      | Debe ser `production`                            |
| `KEY49_OTEL_TRACES_EXPORTER` | `otlp` para exportar a Grafana Tempo             |
| `KEY49_OTEL_ENDPOINT`        | URL del collector OTLP (ej: `http://tempo:4317`) |
| `KEY49_SMTP_START_TLS`       | `REQUIRED` para servidores SMTP reales           |

### Virtual Threads y Dimensionamiento de Pools

Key49 ejecuta con **virtual threads habilitados** (`quarkus.virtual-threads.enabled=true`). Esto significa:

- Las operaciones con `@Blocking` (consumers RabbitMQ, clientes SOAP del SRI) se ejecutan en virtual threads, no en platform threads del pool.
- Los **event loops** de Vert.x manejan I/O no-bloqueante (HTTP, Redis, RabbitMQ). Su tamaño se auto-configura como `2 × cores`. No se debe sobreescribir salvo en casos excepcionales.
- El **thread pool** (`KEY49_THREAD_POOL_MAX=50`) es un fallback para tareas que no usan virtual threads. Con virtual threads activos, su importancia es menor, pero se mantiene como límite de seguridad.

**Recomendaciones de producción:**

| Parámetro               | Fórmula sugerida                         | Ejemplo (4 cores, 20 tenants) |
| ----------------------- | ---------------------------------------- | ----------------------------- |
| `KEY49_DB_POOL_MAX`     | `min(num_tenants × 2 + 10, 50)`          | `50`                          |
| `KEY49_DB_POOL_MIN`     | `5` (suficiente para conexiones idle)    | `5`                           |
| `KEY49_THREAD_POOL_MAX` | `50` (fallback, virtual threads dominan) | `50`                          |
| Event loops (auto)      | `2 × cores` (no configurar)              | `8`                           |

**Métricas a monitorear:**

- `agroal.active.count` — conexiones activas en el pool
- `agroal.awaiting.count` — requests esperando conexión (alerta si > 0 sostenido)
- `agroal.max.used.count` — pico de conexiones usadas (ajustar `max-size` si se acerca al tope)

### Prefetch de Consumers RabbitMQ

El **prefetch** controla cuántos mensajes un consumer puede tener en vuelo (no confirmados) simultáneamente. Valores diferenciados por canal optimizan el rendimiento según la latencia de cada operación:

| Consumer     | Variable                            | Default | Justificación                                         |
| ------------ | ----------------------------------- | ------- | ----------------------------------------------------- |
| Firma (sign) | `KEY49_RABBITMQ_PREFETCH_SIGN`      | `10`    | CPU-bound (XML + XAdES), se beneficia de buffering    |
| Envío (send) | `KEY49_RABBITMQ_PREFETCH_SEND`      | `5`     | SOAP al SRI es lento (~2-5s), limitar para no saturar |
| Autorización | `KEY49_RABBITMQ_PREFETCH_AUTHORIZE` | `5`     | SOAP al SRI, misma razón que envío                    |
| Notificación | `KEY49_RABBITMQ_PREFETCH_NOTIFY`    | `10`    | Email + webhook son relativamente rápidos             |
| DLQ          | `KEY49_RABBITMQ_PREFETCH_DLQ`       | `5`     | Procesamiento de errores, sin urgencia                |

**Recomendaciones:**

- Si el SRI responde lento (>5s promedio), reducir `PREFETCH_SEND` y `PREFETCH_AUTHORIZE` a `2-3`.
- Si se necesitan más throughput en firma/notificación, subir hasta `20`.
- Monitorear `rabbitmq_queue_messages_unacked` para detectar consumers saturados.

### Outbox Poller

El Outbox Poller lee eventos pendientes de la tabla `outbox` de cada tenant y los publica a RabbitMQ. Características de alto rendimiento:

- **Batch-size configurable**: `KEY49_OUTBOX_BATCH_SIZE` (default 50). En producción con alto volumen, subir a 200.
- **FOR UPDATE SKIP LOCKED**: permite ejecutar múltiples instancias del poller en paralelo sin bloqueos.
- **Polling adaptativo**: el flag `lastCycleHadEvents` indica si el ciclo anterior encontró eventos, útil para monitoreo.

**Métricas Micrometer:**

| Métrica                      | Tipo    | Descripción                                   |
| ---------------------------- | ------- | --------------------------------------------- |
| `key49.outbox.events.polled` | Counter | Total de eventos outbox publicados a RabbitMQ |
| `key49.outbox.poll.duration` | Timer   | Duración de cada ciclo de polling             |

**Recomendaciones de tuning:**

- Si `key49.outbox.poll.duration` promedio > 400ms, reducir `KEY49_OUTBOX_BATCH_SIZE` o revisar performance de la BD.
- Si `key49.outbox.events.polled` crece rápido y hay acumulación en la tabla outbox, reducir `KEY49_OUTBOX_POLL_INTERVAL` a 200ms y aumentar batch-size.
- En modo multi-instancia (futuro): el `FOR UPDATE SKIP LOCKED` garantiza que dos pollers no procesen el mismo evento.

### Caché de API Keys en Redis

Cada request HTTP autentica la API key. Para evitar una consulta SQL por request, los datos de autenticación se cachean en Redis con TTL configurable.

- **Key**: `key49:apikey:{sha256_hash}` → hash con `tenant_id`, `schema_name`, `rate_limit_rpm`, `key_status`, `tenant_status`, `expires_at`
- **TTL**: `KEY49_API_KEY_CACHE_TTL_SECONDS` (default 300s = 5 minutos)
- **Invalidación automática**: al revocar una API key, se elimina la entrada de Redis
- **Fallback**: si Redis no está disponible, el filtro consulta PostgreSQL directamente (degradación graceful)

**Consideraciones de seguridad:**

- Al revocar una key, la invalidación es inmediata. Sin embargo, si Redis falla justo después de revocar, la key cacheada podría seguir activa hasta que expire el TTL.
- Para entornos de alta seguridad, reducir `KEY49_API_KEY_CACHE_TTL_SECONDS` a 60s.

---

## Crear un tenant

### Paso 1: Registrar en tabla `public.tenants`

```sql
INSERT INTO tenants (
    tenant_id, ruc, legal_name, trade_name, main_address,
    environment, schema_name, rate_limit_rpm
) VALUES (
    gen_random_uuid(),
    '0991234567001',              -- RUC del contribuyente (13 dígitos)
    'Mi Empresa S.A.',            -- Razón social
    'Mi Empresa',                 -- Nombre comercial
    'Guayaquil, Av. 9 de Octubre',-- Dirección matriz
    'test',                       -- Ambiente: test | production
    'tenant_miempresa',           -- Nombre del esquema PostgreSQL
    100                           -- Rate limit (requests por minuto)
);
```

### Paso 2: Crear el esquema PostgreSQL

```sql
CREATE SCHEMA tenant_miempresa;
```

### Paso 3: Ejecutar los scripts de migración del tenant

```bash
DB_HOST=localhost
DB_PORT=5433
DB_NAME=key49
DB_USER=postgres
SCHEMA=tenant_miempresa
export PGPASSWORD=1234abcd

# Crear tablas en el esquema del tenant
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

### Paso 4: Verificar las tablas del tenant

```sql
SET search_path TO tenant_miempresa;
\dt
```

Resultado esperado:

```
               List of relations
     Schema       |        Name         | Type  |  Owner
------------------+---------------------+-------+----------
 tenant_miempresa | audit_log           | table | postgres
 tenant_miempresa | documents           | table | postgres
 tenant_miempresa | outbox              | table | postgres
 tenant_miempresa | webhook_deliveries  | table | postgres
```

---

## Generar una API Key

### Paso 1: Elegir el raw key

El formato del API key es: `fec_{environment}_{random}` donde:

- `fec_test_` para ambiente de pruebas
- `fec_live_` para producción

Ejemplo: `fec_test_MiEmpresa2026TestKey001`

### Paso 2: Calcular el hash SHA-256

```bash
echo -n "fec_test_MiEmpresa2026TestKey001" | sha256sum | cut -d' ' -f1
```

### Paso 3: Insertar en la base de datos

```sql
-- Obtener el tenant_id del tenant
SELECT tenant_id FROM tenants WHERE ruc = '0991234567001';

-- Insertar la API key (usar el hash calculado)
INSERT INTO api_keys (
    api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status
) VALUES (
    gen_random_uuid(),
    'UUID_DEL_TENANT',            -- Reemplazar con el tenant_id obtenido
    'fec_test',                   -- Prefijo (primeros 8 caracteres)
    'HASH_SHA256_CALCULADO',      -- Reemplazar con el hash
    'API Key de pruebas',         -- Nombre descriptivo
    '*',                          -- Permisos (por ahora, todos)
    'active'                      -- Estado
);
```

### Paso 4: Verificar

```bash
curl -s http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer fec_test_MiEmpresa2026TestKey001" | jq .
```

---

## Script automatizado para desarrollo

El script `db/init-dev.sh` automatiza todo lo anterior para un ambiente de desarrollo local:

```bash
# Desde la raíz del proyecto
chmod +x db/init-dev.sh
./db/init-dev.sh
```

Crea automáticamente:

- Tablas del esquema `public`
- Tenant demo: `Empresa Demo S.A.` (RUC: `1790016919001`)
- Esquema: `tenant_demo` con todas las tablas
- API Key: `fec_test_DemoKey49DevLocalTest00`
- Bucket MinIO: `key49-documents`

---

## Arrancar la aplicación

### Modo desarrollo (Quarkus Dev Mode)

```bash
# Compilar e iniciar en dev mode
mvn quarkus:dev
```

Quarkus Dev Mode incluye:

- Hot reload automático al cambiar código
- DevServices (PostgreSQL y Redis de test en containers)
- Dev UI en `http://localhost:8080/q/dev-ui/`
- Mock del mailer (no envía emails reales)

### Modo producción

```bash
# Compilar el proyecto
mvn clean package -DskipTests

# Ejecutar el uber-jar
java -jar target/quarkus-app/quarkus-run.jar
```

O con compilación nativa:

```bash
# Compilar imagen nativa (requiere GraalVM)
mvn clean package -Pnative -DskipTests

# Ejecutar
./target/key49-*-runner
```

### Puertos y URLs

| Servicio       | URL                                    |
| -------------- | -------------------------------------- |
| API REST       | `http://localhost:8080/v1/`            |
| Portal Web     | `http://localhost:8080/portal/login`   |
| Health Check   | `http://localhost:8080/q/health`       |
| Health Ready   | `http://localhost:8080/q/health/ready` |
| Health Live    | `http://localhost:8080/q/health/live`  |
| OpenAPI (JSON) | `http://localhost:8080/q/openapi`      |
| Dev UI         | `http://localhost:8080/q/dev-ui/`      |
| RabbitMQ Mgmt  | `http://localhost:15672`               |
| MinIO Console  | `http://localhost:9001`                |

---

## Verificar el despliegue

### 1. Health checks

```bash
# Health general
curl -s http://localhost:8080/q/health | jq .

# Solo readiness (BD, Redis, RabbitMQ, MinIO)
curl -s http://localhost:8080/q/health/ready | jq .

# Solo liveness (SRI endpoints)
curl -s http://localhost:8080/q/health/live | jq .
```

Respuesta esperada (`status: "UP"`):

```json
{
  "status": "UP",
  "checks": [
    { "name": "PostgreSQL connection", "status": "UP" },
    { "name": "Redis connection", "status": "UP" },
    { "name": "RabbitMQ connection", "status": "UP" },
    { "name": "MinIO bucket", "status": "UP" },
    { "name": "SRI Reception WSDL", "status": "UP" },
    { "name": "SRI Authorization WSDL", "status": "UP" }
  ]
}
```

### 2. Autenticación

```bash
# Con API key del tenant demo
curl -s http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

### 3. Listar facturas (vacío inicialmente)

```bash
curl -s http://localhost:8080/v1/invoices \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

### 4. Crear una factura de prueba

```bash
curl -s -X POST http://localhost:8080/v1/invoices \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "'$(date +%Y-%m-%d)'",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "address": "Quito, Av. Principal 123",
      "email": "test@example.com"
    },
    "items": [{
      "main_code": "SRV-001",
      "description": "Servicio de prueba",
      "quantity": 1,
      "unit_price": 100.00,
      "discount": 0.00,
      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.0 }]
    }],
    "payments": [{
      "payment_method": "01",
      "total": 115.00
    }]
  }' | jq .
```

### 5. Portal Web

Abrir `http://localhost:8080/portal/login` en el navegador e ingresar la API key: `fec_test_DemoKey49DevLocalTest00`.

### 6. Métricas

```bash
curl -s http://localhost:8080/v1/metrics/summary \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

---

## Troubleshooting

### PostgreSQL no accesible

```
ERROR: connection refused (localhost:5433)
```

**Solución**: Verificar que PostgreSQL esté corriendo en el puerto correcto.

```bash
pg_isready -h localhost -p 5433
```

### Redis no disponible

```
WARN: Redis connection failed, rate limiting disabled
```

Key49 funciona sin Redis pero sin rate limiting ni sesiones del portal.

**Solución**: Verificar que Redis esté corriendo.

```bash
redis-cli ping
```

### RabbitMQ: colas no creadas

```
ERROR: Exchange key49.documents not found
```

**Solución**: RabbitMQ crea las colas automáticamente al arrancar Key49. Si el problema persiste, reiniciar RabbitMQ.

```bash
docker compose restart rabbitmq
```

### MinIO: bucket no encontrado

```
ERROR: Bucket key49-documents does not exist
```

**Solución**: Crear el bucket manualmente (ver sección [MinIO](#minio-almacenamiento)).

### Error de autenticación (401)

```json
{ "error": { "code": "UNAUTHORIZED", "message": "API key invalid" } }
```

**Causas posibles**:

- API key incorrecto — verificar el valor exacto
- Hash SHA-256 incalculado — recalcular con `echo -n "key" | sha256sum`
- API key revocado — verificar `status = 'active'` en tabla `api_keys`
- Tenant suspendido — verificar `status = 'active'` en tabla `tenants`

### Error de zona horaria

```json
{ "error": { "code": "INVALID_ISSUE_DATE" } }
```

**Causa**: La `issue_date` no coincide con la fecha actual en zona `America/Guayaquil` (UTC-5).

**Solución**: Usar la fecha del día en Ecuador:

```bash
TZ=America/Guayaquil date +%Y-%m-%d
```

### Error de certificado

```json
{ "error": { "code": "CERTIFICATE_NOT_CONFIGURED" } }
```

**Causa**: El tenant no tiene certificado .p12 configurado.

**Solución**: Subir certificado vía API:

```bash
curl -X POST http://localhost:8080/v1/tenant/certificate \
  -H "Authorization: Bearer fec_test_..." \
  -F "certificate=@/ruta/al/certificado.p12" \
  -F "password=contraseña_del_certificado"
```

### Documento duplicado (409)

```json
{
  "error": {
    "code": "DUPLICATE_DOCUMENT",
    "message": "...",
    "existingDocument": {
      "id": "uuid",
      "status": "AUTHORIZED",
      "accessKey": "49 dígitos",
      "authorizationDate": "..."
    }
  }
}
```

**Causa**: Ya existe un documento activo o completado con la misma combinación de `document_type + establishment + issue_point + sequence_number`.

**Nota**: Si el documento existente estaba en estado `REJECTED` o `FAILED`, el sistema lo recicla automáticamente con los nuevos datos y retorna **202 Accepted** (no 409). Esto permite al cliente corregir errores y reenviar sin cambiar el secuencial.

### Rate limit excedido (429)

```json
{ "error": { "code": "RATE_LIMIT_EXCEEDED" } }
```

**Causa**: Se superó el límite de requests por minuto del tenant.

**Solución**: Esperar el tiempo indicado en el header `Retry-After` o aumentar `rate_limit_rpm` en la tabla `tenants`.

### Logs de la aplicación

```bash
# Ver logs en dev mode (ya incluidos en consola)
# En producción, revisar stdout o el sistema de logs configurado

# Filtrar por componente
grep "auracore.key49" /var/log/key49.log

# Buscar errores
grep "ERROR" /var/log/key49.log
```

### Verificar colas RabbitMQ

Acceder a la consola de management: `http://localhost:15672` (guest/guest).

Verificar que existen las colas:

- `key49.sign`
- `key49.send`
- `key49.authorize`
- `key49.notify`
- `key49.dlq`

Y que los mensajes fluyen sin acumularse.

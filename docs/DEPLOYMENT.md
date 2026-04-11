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
10. [Despliegue sin pérdida de mensajes](#despliegue-sin-pérdida-de-mensajes)
11. [Docker en producción](#docker-en-producción)
12. [Troubleshooting](#troubleshooting)

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

| Variable                                 | Default                             | Descripción                                    |
| ---------------------------------------- | ----------------------------------- | ---------------------------------------------- |
| `KEY49_DB_USER`                          | `postgres`                          | Usuario PostgreSQL                             |
| `KEY49_DB_PASSWORD`                      | `1234abcd`                          | Contraseña PostgreSQL                          |
| `KEY49_DB_REACTIVE_URL`                  | `postgresql://localhost:5433/key49` | URL de conexión reactiva                       |
| `KEY49_REDIS_URL`                        | `redis://localhost:6379`            | URL de Redis                                   |
| `KEY49_RABBITMQ_HOST`                    | `localhost`                         | Host de RabbitMQ                               |
| `KEY49_RABBITMQ_PORT`                    | `5672`                              | Puerto de RabbitMQ                             |
| `KEY49_RABBITMQ_USER`                    | `guest`                             | Usuario de RabbitMQ                            |
| `KEY49_RABBITMQ_PASSWORD`                | `guest`                             | Contraseña de RabbitMQ                         |
| `KEY49_RABBITMQ_PREFETCH_SIGN`           | `10`                                | Prefetch del consumer de firma                 |
| `KEY49_RABBITMQ_PREFETCH_SEND`           | `5`                                 | Prefetch del consumer de envío SRI             |
| `KEY49_RABBITMQ_PREFETCH_AUTHORIZE`      | `5`                                 | Prefetch del consumer de autorización          |
| `KEY49_RABBITMQ_PREFETCH_NOTIFY`         | `10`                                | Prefetch del consumer de notificación          |
| `KEY49_RABBITMQ_PREFETCH_DLQ`            | `5`                                 | Prefetch del consumer DLQ                      |
| `KEY49_STORAGE_ENDPOINT`                 | `http://localhost:9000`             | Endpoint de MinIO/S3                           |
| `KEY49_STORAGE_ACCESS_KEY`               | `minioadmin`                        | Access key de MinIO                            |
| `KEY49_STORAGE_SECRET_KEY`               | `minioadmin`                        | Secret key de MinIO                            |
| `KEY49_STORAGE_BUCKET`                   | `key49-documents`                   | Nombre del bucket                              |
| `KEY49_STORAGE_REGION`                   | `us-east-1`                         | Región S3                                      |
| `KEY49_STORAGE_CONNECT_TIMEOUT_S`        | `5`                                 | Timeout conexión MinIO (s)                     |
| `KEY49_STORAGE_WRITE_TIMEOUT_S`          | `30`                                | Timeout escritura MinIO (s)                    |
| `KEY49_STORAGE_READ_TIMEOUT_S`           | `15`                                | Timeout lectura MinIO (s)                      |
| `KEY49_TIMEZONE`                         | `America/Guayaquil`                 | Zona horaria (UTC-5)                           |
| `KEY49_SRI_ENVIRONMENT`                  | `test`                              | Ambiente SRI: `test` o `production`            |
| `KEY49_SRI_RECEPTION_TIMEOUT_MS`         | `3000`                              | Timeout de recepción SOAP (ms)                 |
| `KEY49_SRI_AUTHORIZATION_TIMEOUT_MS`     | `5000`                              | Timeout de autorización SOAP (ms)              |
| `KEY49_SRI_URL_TEST_RECEPTION`           | (URL celcer)                        | URL SOAP recepción (test)                      |
| `KEY49_SRI_URL_TEST_AUTHORIZATION`       | (URL celcer)                        | URL SOAP autorización (test)                   |
| `KEY49_SRI_URL_PRODUCTION_RECEPTION`     | (URL cel)                           | URL SOAP recepción (producción)                |
| `KEY49_SRI_URL_PRODUCTION_AUTHORIZATION` | (URL cel)                           | URL SOAP autorización (producción)             |
| `KEY49_SRI_MAX_RETRIES`                  | `6`                                 | Máximo de reintentos SRI                       |
| `KEY49_EMAIL_FROM`                       | `facturacion@key49.ec`              | Email remitente                                |
| `KEY49_EMAIL_ENABLED`                    | `true`                              | Habilitar envío de emails                      |
| `KEY49_SMTP_HOST`                        | `localhost`                         | Servidor SMTP                                  |
| `KEY49_SMTP_PORT`                        | `1025`                              | Puerto SMTP                                    |
| `KEY49_SMTP_USER`                        | (vacío)                             | Usuario SMTP                                   |
| `KEY49_SMTP_PASSWORD`                    | (vacío)                             | Contraseña SMTP                                |
| `KEY49_SMTP_START_TLS`                   | `DISABLED`                          | StartTLS para SMTP                             |
| `KEY49_SMTP_SSL`                         | `false`                             | SSL para SMTP                                  |
| `KEY49_WEBHOOK_ENABLED`                  | `true`                              | Habilitar webhooks                             |
| `KEY49_WEBHOOK_CONNECT_TIMEOUT_MS`       | `5000`                              | Timeout de conexión webhook (ms)               |
| `KEY49_WEBHOOK_READ_TIMEOUT_MS`          | `10000`                             | Timeout de lectura webhook (ms)                |
| `KEY49_OUTBOX_POLL_INTERVAL`             | `500ms`                             | Intervalo del outbox poller                    |
| `KEY49_OUTBOX_BATCH_SIZE`                | `50`                                | Tamaño del batch del outbox                    |
| `KEY49_RETRY_POLL_INTERVAL`              | `5s`                                | Intervalo del retry poller                     |
| `KEY49_API_KEY_CACHE_TTL_SECONDS`        | `300`                               | TTL del caché de API keys en Redis (s)         |
| `KEY49_TENANT_CACHE_TTL_SECONDS`         | `600`                               | TTL del caché de tenants en Redis (s)          |
| `KEY49_CERT_CACHE_TTL_MINUTES`           | `30`                                | TTL del caché de certificados en memoria (min) |
| `KEY49_CERT_CACHE_MAX_ENTRIES`           | `100`                               | Máximo de certificados en caché                |
| `KEY49_DB_POOL_MIN`                      | `5`                                 | Conexiones mínimas del pool Agroal             |
| `KEY49_DB_POOL_MAX`                      | `50`                                | Conexiones máximas del pool Agroal             |
| `KEY49_DB_POOL_ACQUISITION_TIMEOUT`      | `5S`                                | Timeout para obtener conexión                  |
| `KEY49_DB_POOL_IDLE_REMOVAL_INTERVAL`    | `2M`                                | Intervalo de limpieza de ociosas               |
| `KEY49_DB_POOL_MAX_LIFETIME`             | `30M`                               | Vida máxima de una conexión                    |
| `KEY49_THREAD_POOL_MAX`                  | `50`                                | Platform threads máximos (fallback)            |

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

**Recomendaciones de producción (con PgBouncer):**

| Parámetro               | Sin PgBouncer                   | Con PgBouncer                  |
| ----------------------- | ------------------------------- | ------------------------------ |
| `KEY49_DB_POOL_MAX`     | `min(num_tenants × 2 + 10, 50)` | `20` (PgBouncer gestiona pool) |
| `KEY49_DB_POOL_MIN`     | `5`                             | `2`                            |
| `KEY49_THREAD_POOL_MAX` | `50`                            | `50`                           |
| Event loops (auto)      | `2 × cores`                     | `2 × cores`                    |

**Métricas a monitorear:**

- `agroal.active.count` — conexiones activas en el pool
- `agroal.awaiting.count` — requests esperando conexión (alerta si > 0 sostenido)
- `agroal.max.used.count` — pico de conexiones usadas (ajustar `max-size` si se acerca al tope)

### PgBouncer como Connection Pooler

En producción con múltiples tenants, se recomienda colocar **PgBouncer** entre Key49 y PostgreSQL. PgBouncer gestiona un pool de conexiones al servidor PostgreSQL y multiplexa las peticiones de los clientes (Agroal), reduciendo la cantidad de conexiones reales al servidor.

**Modo `transaction`:**

PgBouncer opera en modo `transaction` — la conexión al servidor se asigna solo durante la duración de una transacción. Al finalizar la transacción, la conexión regresa al pool del servidor y puede ser reutilizada por otro cliente. Este modo es compatible con la multi-tenancy de Key49 porque se usa `SET LOCAL search_path` (se resetea automáticamente al terminar cada transacción).

**Configuración del docker-compose:**

```yaml
pgbouncer:
  image: edoburu/pgbouncer:1.23.1-p2
  depends_on:
    postgres:
      condition: service_healthy
  ports:
    - "6432:6432"
  volumes:
    - ./docker/pgbouncer/pgbouncer.ini:/etc/pgbouncer/pgbouncer.ini:ro
    - ./docker/pgbouncer/userlist.txt:/etc/pgbouncer/userlist.txt:ro
```

**Parámetros de PgBouncer (`docker/pgbouncer/pgbouncer.ini`):**

| Parámetro             | Valor         | Descripción                                         |
| --------------------- | ------------- | --------------------------------------------------- |
| `pool_mode`           | `transaction` | Conexión asignada por transacción, no por sesión    |
| `max_client_conn`     | `200`         | Conexiones máximas de clientes (Agroal → PgBouncer) |
| `default_pool_size`   | `25`          | Conexiones al servidor PostgreSQL por base de datos |
| `reserve_pool_size`   | `5`           | Conexiones adicionales en caso de picos             |
| `server_idle_timeout` | `300`         | Cierra conexiones idle al servidor tras 5 minutos   |
| `server_lifetime`     | `3600`        | Vida máxima de una conexión al servidor (1 hora)    |

**Conexión desde Key49:**

En producción, apuntar la URL JDBC a PgBouncer (puerto 6432) en lugar de PostgreSQL directo:

```bash
KEY49_DB_JDBC_URL=jdbc:postgresql://pgbouncer:6432/key49
```

**Compatibilidad con `SET LOCAL search_path`:**

Key49 usa `SET LOCAL search_path TO 'tenant_xxx', public` dentro de cada `@Transactional`. `SET LOCAL` tiene scope de transacción y se resetea automáticamente al hacer commit/rollback. Esto garantiza que cuando PgBouncer devuelve la conexión al pool, el `search_path` vuelve al default — sin riesgo de que un tenant vea datos de otro.

**Nota sobre `SET application_name`:** Se removió `new-connection-sql=SET application_name` de la configuración de Agroal ya que es un comando de nivel sesión incompatible con PgBouncer en modo `transaction`. PgBouncer añade `ignore_startup_parameters=application_name` para manejar esto.

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

### Caché de Metadatos de Tenant en Redis

Los consumers de RabbitMQ y otros servicios consultan datos del tenant (environment, webhook, email, etc.) en cada operación. Para evitar queries repetitivos a `public.tenants`, los metadatos se cachean en Redis.

- **Key principal**: `key49:tenant:{uuid}` → hash con todos los campos del tenant (sin certificado binario)
- **Índice secundario**: `key49:tenant:schema:{schema_name}` → string con el `tenant_id` (para lookups por esquema)
- **TTL**: `KEY49_TENANT_CACHE_TTL_SECONDS` (default 600s = 10 minutos)
- **Invalidación automática**: al actualizar el perfil o subir un certificado, se eliminan ambas keys de Redis
- **Fallback**: si Redis no está disponible, se consulta PostgreSQL directamente
- **Exclusiones**: `certificate_p12` y `certificate_password_enc` NO se almacenan en Redis (datos binarios sensibles)

**Componentes que usan el caché:**

- `SendConsumer`, `AuthorizeConsumer`, `NotifyConsumer` — lookup por `schema_name`
- `ConsumerErrorHandler` — lookup por `schema_name` para dispatch de webhooks
- `MetricsService` — lookup por `tenant_id`
- `SignConsumer` — NO usa caché de tenant (necesita certificado binario, usa caché de certificados en memoria)

---

### Caché de Certificados .p12 en Memoria

El `SignConsumer` descifra el certificado .p12 y parsea el PKCS12 KeyStore en cada firma de documento (~50ms por operación). Para evitar esta operación repetitiva, los datos parseados (`PrivateKey + X509Certificate + chain`) se cachean en memoria local.

- **Key**: `tenant_id` (UUID)
- **Almacenamiento**: `ConcurrentHashMap` en memoria JVM (no Redis, datos sensibles)
- **TTL**: `KEY49_CERT_CACHE_TTL_MINUTES` (default 30 minutos)
- **Máximo de entradas**: `KEY49_CERT_CACHE_MAX_ENTRIES` (default 100)
- **Invalidación automática**: al subir nuevo certificado (`TenantAdminService.uploadCertificate()`)
- **Seguridad**: los bytes descifrados del .p12 y la contraseña se limpian de memoria tras el parsing; solo se retiene el `PrivateKey` y `X509Certificate` parseados

**Tuning:**

- En entornos con muchos tenants activos, incrementar `KEY49_CERT_CACHE_MAX_ENTRIES`.
- Para renovación rápida de certificados, reducir `KEY49_CERT_CACHE_TTL_MINUTES`.

### Circuit Breaker para SRI SOAP

Los clientes SOAP `SriReceptionClient` y `SriAuthorizationClient` implementan Circuit Breaker (MicroProfile Fault Tolerance) para evitar cascadas de fallos cuando el SRI está caído.

**Parámetros del Circuit Breaker:**

| Parámetro                | Valor | Descripción                                          |
| ------------------------ | ----- | ---------------------------------------------------- |
| `requestVolumeThreshold` | `10`  | Mínimo de llamadas antes de evaluar el circuito      |
| `failureRatio`           | `0.5` | 50% de fallos abre el circuito                       |
| `delay`                  | `30s` | Tiempo en estado _open_ antes de pasar a _half-open_ |
| `successThreshold`       | `3`   | Llamadas exitosas para cerrar el circuito            |

**Timeouts (Fault Tolerance `@Timeout`):**

| Cliente           | Timeout |
| ----------------- | ------- |
| Recepción SOAP    | 3s      |
| Autorización SOAP | 5s      |

**Comportamiento:**

- Cuando el circuito está **abierto**, las llamadas fallan inmediatamente con `CircuitBreakerOpenException` → el documento va a RETRY con backoff exponencial (no se pierde).
- Cuando expira el `delay`, el circuito pasa a **half-open** y permite `successThreshold` llamadas de prueba.
- Si las llamadas de prueba tienen éxito, el circuito se **cierra** y se reanuda el flujo normal.

**URLs del SRI configurables:**

Los endpoints SOAP del SRI son configurables vía variables de entorno. Por defecto apuntan a los endpoints oficiales del SRI:

- `KEY49_SRI_URL_TEST_RECEPTION` → `https://celcer.sri.gob.ec/.../RecepcionComprobantesOffline?wsdl`
- `KEY49_SRI_URL_TEST_AUTHORIZATION` → `https://celcer.sri.gob.ec/.../AutorizacionComprobantesOffline?wsdl`
- `KEY49_SRI_URL_PRODUCTION_RECEPTION` → `https://cel.sri.gob.ec/.../RecepcionComprobantesOffline?wsdl`
- `KEY49_SRI_URL_PRODUCTION_AUTHORIZATION` → `https://cel.sri.gob.ec/.../AutorizacionComprobantesOffline?wsdl`

**Métricas Prometheus:**

- `ft_circuitbreaker_calls_total{method="send", circuitBreakerResult="..."}` — llamadas por resultado (success, failure, circuitBreakerOpen)
- `ft_circuitbreaker_state_total{method="send", state="..."}` — tiempo acumulado en cada estado (open, closed, halfOpen)
- `ft_circuitbreaker_opened_total{method="send"}` — veces que se abrió el circuito

### Timeouts y Circuit Breaker para MinIO

`ObjectStorageService` configura timeouts en el `MinioClient` (OkHttp subyacente) para evitar que consumers se bloqueen indefinidamente si MinIO está caído o lento.

**Timeouts:**

| Variable                          | Default | Descripción                           |
| --------------------------------- | ------- | ------------------------------------- |
| `KEY49_STORAGE_CONNECT_TIMEOUT_S` | `5`     | Timeout para establecer conexión (s)  |
| `KEY49_STORAGE_WRITE_TIMEOUT_S`   | `30`    | Timeout para escritura de objetos (s) |
| `KEY49_STORAGE_READ_TIMEOUT_S`    | `15`    | Timeout para lectura de objetos (s)   |

**Circuit Breaker:**

Los métodos `store()` y `retrieve()` aplican `@CircuitBreaker` con los mismos parámetros que los clientes SRI: `requestVolumeThreshold=10, failureRatio=0.5, delay=30s, successThreshold=3`.

- Cuando el circuito está **abierto**, `CircuitBreakerOpenException` se lanza inmediatamente.
- En `NotifyConsumer`, el almacenamiento en MinIO es **no-bloqueante**: el `catch(Exception)` existente captura la excepción CB y la registra como warning. El documento sigue transitando a NOTIFIED.
- En los endpoints REST de descarga (XML/RIDE), el `StorageExceptionMapper` convierte la excepción en **HTTP 503 Service Unavailable**.

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

## Despliegue sin pérdida de mensajes

Key49 implementa graceful shutdown para garantizar que no se pierden mensajes durante un redeploy.

### Configuración

```properties
# Tiempo máximo que Quarkus espera para que los consumers terminen antes de forzar el shutdown
quarkus.shutdown.timeout=30s
```

### Comportamiento al apagar

1. **Quarkus recibe SIGTERM** (o `shutdown` command)
2. **Se detiene la aceptación de nuevos mensajes** — los consumers dejan de recibir de RabbitMQ
3. **Los mensajes en vuelo continúan procesándose** — hasta completar o agotar el timeout
4. **`GracefulShutdownObserver` registra el estado** — reporta cuántos mensajes están en vuelo por consumer
5. **Al expirar el timeout** — si quedan mensajes sin procesar, RabbitMQ los re-encola automáticamente (`basic.nack` con requeue)

### Logs durante shutdown

```
INFO  Graceful shutdown initiated — timeout=30s, in-flight messages=3
INFO    Consumer 'SendConsumer': 2 message(s) in flight
INFO    Consumer 'SignConsumer': 1 message(s) in flight
WARN  3 message(s) still in flight — Quarkus will wait up to 30s before forcing shutdown. Non-acked messages will be requeued by RabbitMQ.
```

Si no hay mensajes en vuelo:

```
INFO  Graceful shutdown initiated — timeout=30s, in-flight messages=0
INFO  No in-flight messages — clean shutdown
```

### Procedimiento de deploy recomendado

```bash
# 1. Verificar el estado de las colas antes del deploy
#    (Management UI: http://localhost:15672 o API)
curl -s -u guest:guest http://localhost:15672/api/queues/%2F/key49.sign | jq '.messages'

# 2. Esperar a que las colas se vacíen (opcional, reduce riesgo)
#    Monitorear que messages ≈ 0 en las colas activas

# 3. Enviar SIGTERM a la aplicación (inicia graceful shutdown)
kill -SIGTERM $(pgrep -f "key49")
# o en Docker:
docker stop --time=35 key49

# 4. Esperar a que la aplicación se detenga (máx 30s + margen)
#    Verificar en logs: "Graceful shutdown initiated"
#    Verificar en logs: "No in-flight messages — clean shutdown"

# 5. Desplegar la nueva versión
java -jar target/quarkus-app/quarkus-run.jar
# o en Docker:
docker run -d --name key49 ...

# 6. Verificar health checks
curl -s http://localhost:8080/q/health | jq .
```

### Garantías de RabbitMQ

- **Prefetched messages**: los mensajes que RabbitMQ ya entregó al consumer pero que no fueron `ack`-eados se re-encolan automáticamente al cerrar la conexión
- **Durabilidad**: las colas `key49.*` son durables — los mensajes persisten en disco
- **Redelivery**: RabbitMQ marca los mensajes re-encolados con `redelivered=true`

### Notas para Docker / Kubernetes

- Configurar `terminationGracePeriodSeconds >= 35` en Kubernetes (5s de margen sobre el timeout de 30s)
- En Docker: usar `docker stop --time=35` para dar suficiente tiempo al graceful shutdown
- La señal `SIGTERM` es la que inicia el shutdown — no usar `SIGKILL` (`docker kill`)

### Backpressure y Monitoreo de Profundidad de Cola

Key49 monitorea la profundidad de las colas RabbitMQ a través de la API de management y expone la información como **readiness health check** y **métricas Micrometer**.

**Health check de readiness:**

- Si alguna cola supera `KEY49_QUEUE_DEPTH_CRITICAL` mensajes, el endpoint `/q/health/ready` reporta `DOWN`.
- El balanceador de carga (o Kubernetes) deja de enviar tráfico a esa instancia hasta que la cola se descongestione.
- Si la API de management de RabbitMQ no responde, el health check reporta `UP` (fail-open — no bloquea tráfico por fallo del monitoreo).

**Variables de configuración:**

| Variable                     | Default | Descripción                                                    |
| ---------------------------- | ------- | -------------------------------------------------------------- |
| `KEY49_QUEUE_DEPTH_CRITICAL` | `5000`  | Umbral para marcar readiness=false (instancia deja de recibir) |
| `KEY49_QUEUE_DEPTH_WARNING`  | `1000`  | Umbral informativo (ya gestionado por alerta T-037)            |

**Métricas Micrometer:**

| Métrica                              | Tipo  | Descripción                                      |
| ------------------------------------ | ----- | ------------------------------------------------ |
| `key49.queue.depth{queue=sign}`      | Gauge | Mensajes pendientes en cola de firma             |
| `key49.queue.depth{queue=send}`      | Gauge | Mensajes pendientes en cola de envío SOAP        |
| `key49.queue.depth{queue=authorize}` | Gauge | Mensajes pendientes en cola de autorización SOAP |
| `key49.queue.depth{queue=notify}`    | Gauge | Mensajes pendientes en cola de notificación      |
| `key49.queue.depth{queue=dlq}`       | Gauge | Mensajes en la cola de dead letters              |

Las métricas se actualizan cada 30 segundos vía la API de management de RabbitMQ (`/api/queues/%2F/{queue}`).

**Recomendaciones:**

- En producción, configurar alertas en Grafana cuando `key49.queue.depth > 1000` sostenido por >5 minutos.
- Si el SRI está caído y las colas crecen, el circuit breaker (T-065) detiene los envíos y el backpressure evita saturar la instancia.
- Ajustar `KEY49_QUEUE_DEPTH_CRITICAL` según la capacidad de la instancia y el volumen esperado.

---

## Docker en producción

### Construir la imagen

El Dockerfile usa **multi-stage build**: Maven compila en un stage temporal y la imagen final contiene solo el JRE Alpine mínimo.

```bash
# Build
docker build -t key49:latest .

# Con tag de versión
docker build -t key49:0.25.7 -t key49:latest .
```

Características de la imagen:

- **Base**: Eclipse Temurin 25 JRE Alpine (~150 MB)
- **Usuario no-root**: `key49` (seguridad)
- **Zona horaria**: `America/Guayaquil` preconfigurada
- **Healthcheck**: `curl http://localhost:8080/q/health/ready` cada 30s
- **Cache de dependencias**: el POM se copia primero para aprovechar capas Docker

### JVM flags de producción

La imagen configura estos flags por defecto vía `JAVA_OPTS`:

| Flag                                    | Propósito                                               |
| --------------------------------------- | ------------------------------------------------------- |
| `-XX:MaxRAMPercentage=75.0`             | Usa hasta 75% de la RAM del contenedor como heap        |
| `-XX:+UseG1GC`                          | Garbage collector G1 (buen balance throughput/latencia) |
| `-XX:+UseStringDeduplication`           | Reduce memoria de strings duplicados (XMLs del SRI)     |
| `-XX:+ExitOnOutOfMemoryError`           | Fuerza exit en OOM para que el orquestador reinicie     |
| `-Djava.security.egd=file:/dev/urandom` | Evita bloqueos en generación de UUIDs/crypto            |

Para sobreescribir flags, pasar `JAVA_OPTS` como variable de entorno:

```bash
# Ejemplo: más memoria, logging GC
docker run -p 8080:8080 \
  -e JAVA_OPTS="-XX:MaxRAMPercentage=80.0 -XX:+UseG1GC -Xlog:gc*:stdout" \
  --env-file .env \
  key49:latest
```

### Ejecutar el contenedor

```bash
# Con archivo de variables de entorno
docker run -d --name key49 \
  -p 8080:8080 \
  --memory=512m \
  --env-file .env \
  key49:latest

# Verificar salud
docker inspect --format='{{.State.Health.Status}}' key49
```

### Dimensionamiento de memoria

| Perfil       | `--memory` | Heap estimado (75%) | Tenants estimados |
| ------------ | ---------- | ------------------- | ----------------- |
| Mínimo       | 256m       | ~192m               | 1-5               |
| Estándar     | 512m       | ~384m               | 5-20              |
| Alto volumen | 1g         | ~768m               | 20-100            |

### GraalVM Native Image (evaluación)

Quarkus soporta compilación nativa con GraalVM para reducir startup (~50ms vs ~2s) y memoria (~50MB vs ~200MB). Sin embargo, **no se recomienda actualmente** para Key49 por:

- La firma XAdES-BES usa Apache Santuario + BouncyCastle que requieren configuración compleja en GraalVM
- Los clientes SOAP generados necesitan reflection config extensiva
- El beneficio de startup rápido es menor en un servicio long-running
- La imagen JVM ya logra ~2s de startup, suficiente para producción

Se reevaluará en futuras versiones si Quarkus mejora el soporte nativo para estas librerías.

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

# Guía Operativa del Sistema — Key49

## Índice

1. [Visión general](#visión-general)
2. [Flujo end-to-end de un comprobante](#flujo-end-to-end-de-un-comprobante)
3. [RabbitMQ — colas y consumers](#rabbitmq--colas-y-consumers)
4. [Reintentos y backoff exponencial](#reintentos-y-backoff-exponencial)
5. [Máquina de estados](#máquina-de-estados)
6. [Circuit breaker](#circuit-breaker)
7. [Redis — caché y resiliencia](#redis--caché-y-resiliencia)
8. [MinIO — almacenamiento de artefactos](#minio--almacenamiento-de-artefactos)
9. [PgBouncer — pool de conexiones](#pgbouncer--pool-de-conexiones)
10. [Outbox pattern](#outbox-pattern)
11. [Webhooks](#webhooks)
12. [Idempotencia](#idempotencia)
13. [Resiliencia ante caídas](#resiliencia-ante-caídas)
14. [Apagado graceful](#apagado-graceful)
15. [Gestión de tenants y planes](#gestión-de-tenants-y-planes)

---

## Visión general

Key49 es una plataforma SaaS multi-tenant que emite comprobantes electrónicos ante el SRI de Ecuador. El sistema orquesta múltiples servicios de infraestructura para procesar cada documento de forma asíncrona y resiliente.

### Componentes del sistema

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   Cliente    │────▶│  Key49 API   │────▶│  PostgreSQL  │
│  (Integrador)│     │  (Quarkus)   │     │  (via PgB.)  │
└──────────────┘     └──────┬───────┘     └──────────────┘
                            │
               ┌────────────┼────────────┐
               ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ RabbitMQ │ │  Redis   │ │  MinIO   │
        │  (colas) │ │ (caché)  │ │ (objetos)│
        └────┬─────┘ └──────────┘ └──────────┘
             │
             ▼
        ┌──────────┐
        │   SRI    │
        │  (SOAP)  │
        └──────────┘
```

| Componente          | Rol                                                       | Puerto                |
| ------------------- | --------------------------------------------------------- | --------------------- |
| **Key49 (Quarkus)** | API REST, consumers, procesamiento                        | 8080                  |
| **PostgreSQL**      | Base de datos relacional, multi-tenant por esquema        | 5432                  |
| **PgBouncer**       | Pool de conexiones entre Key49 y PostgreSQL               | 6432                  |
| **RabbitMQ**        | Colas de mensajes para procesamiento asíncrono            | 5672 / 15672 (admin)  |
| **Redis**           | Caché de API keys, sesiones del portal, rate limiting     | 6379                  |
| **MinIO**           | Almacenamiento S3 de XML firmados, autorizados y RIDE PDF | 9000 / 9001 (console) |
| **SRI**             | Servicio web SOAP del gobierno (Recepción + Autorización) | externo               |

---

## Flujo end-to-end de un comprobante

Un comprobante electrónico (factura, nota de crédito, retención, etc.) atraviesa 6 etapas desde que el integrador envía el request hasta que recibe la notificación. Cada etapa es ejecutada por un consumer independiente conectado a una cola de RabbitMQ.

### Diagrama del flujo

```
                        ┌─────────────────────────────────────────────────────────────────┐
                        │                     Key49 — Pipeline de documento                │
                        │                                                                  │
  POST /v1/invoices     │  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────────┐  │
  ─────────────────────▶│  │  SIGN   │───▶│  SEND   │───▶│AUTHORIZE│───▶│   NOTIFY     │  │
  (JSON del documento)  │  │         │    │         │    │         │    │              │  │
                        │  │• XML    │    │• SOAP   │    │• SOAP   │    │• RIDE (PDF)  │  │
  ◀─────────────────────│  │• Clave  │    │  Recep. │    │  Autor. │    │• MinIO       │  │
  202 Accepted          │  │• Firma  │    │• 3s     │    │• 5s     │    │• Email       │  │
  + document_id         │  │  XAdES  │    │  timeout│    │  timeout│    │• Webhook     │  │
  + access_key          │  └─────────┘    └─────────┘    └─────────┘    └──────────────┘  │
                        │                                                                  │
                        └─────────────────────────────────────────────────────────────────┘
```

### Paso a paso

#### 1. Recepción del request (API)

El integrador envía un `POST /v1/invoices` con el JSON del comprobante y su `Authorization: Bearer k49_...`.

1. **ApiKeyAuthFilter** extrae el Bearer token, computa SHA-256 y busca en Redis (caché 300s). Si no está en caché, consulta PostgreSQL.
2. Se valida que el API key y el tenant estén activos.
3. **TenantContext** se puebla con `tenant_id`, `schema_name` y límites de rate.
4. Se ejecuta `SET LOCAL search_path TO 'tenant_xxx', public` para aislar la transacción al esquema del tenant.
5. Se valida el JSON de entrada (campos requeridos, formatos SRI, fecha = hoy en zona `America/Guayaquil`).
6. Se verifica idempotencia (`X-Idempotency-Key`): si ya existe un documento con esa clave, se retorna el existente.
7. Se verifica unicidad (`document_type + establishment + issue_point + sequence_number`): si existe en estado REJECTED/FAILED se recicla; si está activo se retorna HTTP 409.
8. Se persiste el `Document` en estado **CREATED** y se crea un `OutboxEvent` con tipo `doc.sign` en la **misma transacción**.
9. Se retorna `202 Accepted` con el `document_id` y la `access_key`.

#### 2. Firma digital (SignConsumer)

El **OutboxPoller** (cada 500ms) lee el evento `doc.sign` de la tabla `outbox` y lo publica a RabbitMQ.

1. El **SignConsumer** recibe el mensaje de la cola `key49.sign`.
2. Establece el contexto del tenant (`SET search_path`).
3. Carga el certificado .p12 del tenant (descifra con AES-256-GCM, cacheado en Redis 30min).
4. Genera la **clave de acceso** de 49 dígitos (fecha + tipo + RUC + ambiente + est. + pto. + seq + código numérico + módulo 11).
5. Construye el **XML** según el tipo de documento (InvoiceXmlBuilder, CreditNoteXmlBuilder, etc.) conforme al XSD del SRI.
6. **Firma con XAdES-BES** (Apache Santuario + BouncyCastle): firma enveloped sobre el nodo `comprobante`.
7. Persiste el XML firmado en `doc.original_xml` y la clave de acceso en `doc.access_key`.
8. Transiciona el estado a **SIGNED**.
9. Crea un `OutboxEvent` con tipo `doc.send`.

**Si falla**: el documento pasa a **FAILED** (errores de firma son irrecuperables — certificado inválido, expirado, etc.).

#### 3. Envío al SRI (SendConsumer)

1. El **SendConsumer** recibe el mensaje de la cola `key49.send`.
2. Establece el contexto del tenant.
3. Llama al servicio SOAP de **Recepción** del SRI (`validarComprobante`) con timeout de 3 segundos.
4. Analiza la respuesta:
   - **RECIBIDA**: transiciona a **RECEIVED**, crea evento `doc.authorize`.
   - **DEVUELTA con error de negocio** (códigos 35, 45, 52, 65): transiciona a **REJECTED** (terminal).
   - **Error de infraestructura** (timeout, conexión rechazada, HTTP 500): transiciona a **RETRY** con backoff exponencial.
   - **Circuit breaker abierto**: transiciona a **RETRY** (espera a que el circuito se cierre).

#### 4. Autorización (AuthorizeConsumer)

1. El **AuthorizeConsumer** recibe el mensaje de la cola `key49.authorize`.
2. Establece el contexto del tenant.
3. Llama al servicio SOAP de **Autorización** del SRI (`autorizacionComprobante`) con la clave de acceso y timeout de 5 segundos.
4. Analiza la respuesta:
   - **AUTORIZADO**: guarda `authorization_number` y `authorization_date`, transiciona a **AUTHORIZED**, crea evento `doc.notify`.
   - **NO AUTORIZADO**: transiciona a **REJECTED** (terminal).
   - **Error de infraestructura**: transiciona a **RETRY** con backoff exponencial.

#### 5. Notificación (NotifyConsumer)

1. El **NotifyConsumer** recibe el mensaje de la cola `key49.notify`.
2. Opera en **4 fases** para minimizar el tiempo de transacción:
   - **Fase 1 (transacción corta)**: lee el documento, valida estado AUTHORIZED, lo desconecta del EntityManager.
   - **Fase 2 (sin transacción)**: genera el RIDE (PDF) con código QR. Sube a MinIO el XML firmado, XML autorizado y RIDE PDF.
   - **Fase 3 (transacción corta)**: actualiza las rutas de archivos en la BD, despacha el webhook, transiciona a **NOTIFIED**.
   - **Fase 4 (post-commit)**: envía el email con RIDE + XML adjunto. Si el email falla, el documento queda en NOTIFIED (el email no es bloqueante).
3. El webhook incluye firma HMAC-SHA256 y los datos del documento autorizado.

#### 6. Consulta posterior

El integrador puede:

- **Webhook**: recibe `document.authorized` o `document.rejected` en su URL configurada.
- **Polling**: `GET /v1/invoices/{id}` retorna el estado actual del documento.
- **Portal web**: consulta visual en `/portal/documents/{id}`.

### Tiempos típicos

| Etapa                  | Tiempo típico | Bottleneck                      |
| ---------------------- | ------------- | ------------------------------- |
| API → CREATED          | < 50ms        | Validación JSON + INSERT        |
| OutboxPoller           | 0–500ms       | Intervalo de polling            |
| SIGN                   | 100–300ms     | Firma criptográfica             |
| SEND (SOAP)            | 500ms–3s      | Red + respuesta SRI             |
| AUTHORIZE (SOAP)       | 500ms–5s      | Red + respuesta SRI             |
| NOTIFY                 | 200ms–2s      | RIDE PDF + MinIO upload         |
| **Total (happy path)** | **~2–10s**    | **SRI es el cuello de botella** |

---

## RabbitMQ — colas y consumers

### Topología

Key49 usa un exchange tipo `topic` para enrutar mensajes a las colas correspondientes según la etapa del pipeline.

```
                    ┌────────────────────────────┐
                    │  Exchange: key49.documents  │
                    │  Tipo: topic, durable       │
                    └─────────┬──────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
    routing key:        routing key:        routing key:        routing key:
    doc.sign            doc.send            doc.authorize       doc.notify
          │                   │                   │                   │
          ▼                   ▼                   ▼                   ▼
  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
  │  key49.sign  │   │  key49.send  │   │key49.authorize│  │ key49.notify │
  │ prefetch=10  │   │ prefetch=5   │   │  prefetch=5  │   │ prefetch=10  │
  └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
         │                  │                   │                   │
         ▼                  ▼                   ▼                   ▼
   SignConsumer       SendConsumer       AuthorizeConsumer    NotifyConsumer
```

### Formato del mensaje

Cada mensaje en las colas es un JSON con la estructura de `DocumentEvent`:

```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "tenantSchemaName": "tenant_abc123",
  "eventType": "doc.sign",
  "retryCount": 0,
  "payload": "{}"
}
```

El campo `tenantSchemaName` permite al consumer ejecutar `SET search_path` sin consultar la BD para obtener el esquema.

### Prefetch por consumer

El prefetch controla cuántos mensajes el consumer toma de la cola antes de confirmarlos. Un prefetch alto aumenta throughput pero consume más memoria.

| Consumer              | Prefetch | Justificación                                                    |
| --------------------- | -------- | ---------------------------------------------------------------- |
| **SignConsumer**      | 10       | CPU-bound (firma criptográfica), puede paralelizar               |
| **SendConsumer**      | 5        | I/O-bound + `@Blocking` (SOAP con timeout 3s), SRI es bottleneck |
| **AuthorizeConsumer** | 5        | I/O-bound + `@Blocking` (SOAP con timeout 5s)                    |
| **NotifyConsumer**    | 10       | CPU-heavy (RIDE PDF) pero paralelizable                          |
| **DlqConsumer**       | 5        | Bajo volumen, solo errores terminales                            |

Configuración vía variables de entorno:

```bash
KEY49_RABBITMQ_PREFETCH_SIGN=10
KEY49_RABBITMQ_PREFETCH_SEND=5
KEY49_RABBITMQ_PREFETCH_AUTHORIZE=5
KEY49_RABBITMQ_PREFETCH_NOTIFY=10
KEY49_RABBITMQ_PREFETCH_DLQ=5
```

### InFlightTracker

Cada consumer registra sus mensajes en procesamiento mediante `InFlightTracker`. Esto permite:

- Monitorear cuántos mensajes están siendo procesados por consumer.
- En shutdown graceful, saber cuántos mensajes faltan por completar.

### Dead Letter Queue (DLQ)

La cola `key49.dlq` recibe mensajes que RabbitMQ no pudo entregar (rechazados, expirados, o sin consumer). El **DlqConsumer** marca esos documentos como **FAILED**.

---

## Reintentos y backoff exponencial

### Principio fundamental

Solo los **errores de infraestructura** se reintentan. Los **errores de negocio** del SRI son terminales.

### Errores que SÍ se reintentan

| Error                         | Consumer afectado | Causa                                |
| ----------------------------- | ----------------- | ------------------------------------ |
| `TimeoutException`            | Send, Authorize   | SRI no respondió a tiempo            |
| `CircuitBreakerOpenException` | Send, Authorize   | Circuito abierto por fallos previos  |
| Conexión rechazada            | Send, Authorize   | SRI caído o red inaccesible          |
| HTTP 500 del SRI              | Send, Authorize   | Error interno del servidor SRI       |
| Código SRI 43                 | Send              | Clave duplicada (se puede regenerar) |

### Errores que NO se reintentan (van a REJECTED o FAILED)

| Código SRI                    | Significado                    | Estado final |
| ----------------------------- | ------------------------------ | ------------ |
| 35                            | Comprobante ya registrado      | REJECTED     |
| 45                            | Fecha fuera de rango permitido | REJECTED     |
| 52                            | Estructura XML inválida        | REJECTED     |
| 65                            | Fecha futura                   | REJECTED     |
| Otros errores de negocio      | Validación de datos SRI        | REJECTED     |
| Certificado inválido/expirado | Error en firma                 | FAILED       |
| XML no generado               | Error en builder               | FAILED       |

### Secuencia de backoff

```
Intento 1: inmediato (primer procesamiento)
Intento 2: espera   5 segundos
Intento 3: espera  15 segundos
Intento 4: espera  45 segundos
Intento 5: espera 135 segundos  (~2 min)
Intento 6: espera 405 segundos  (~7 min)
───────────────────────────────
Total máximo: ~10 minutos antes de declarar FAILED
```

### Implementación

El backoff NO usa colas con TTL ni delayed exchanges de RabbitMQ. Se implementa con una columna `next_retry_at` en la tabla `documents`:

```
1. Consumer detecta error de infra → incrementa retryCount
2. Calcula next_retry_at = ahora + delay[retryCount]
3. Transiciona el documento a RETRY
4. RetryPoller (cada 5s) busca documentos con status=RETRY AND next_retry_at <= ahora
5. Crea OutboxEvent para re-encolar → OutboxPoller lo publica a RabbitMQ
6. El consumer correspondiente lo procesa de nuevo
```

Ventaja: la base de datos es la fuente de verdad. Si RabbitMQ se reinicia, los documentos en RETRY no se pierden.

### RetryPoller

Job programado que corre cada 5 segundos (configurable con `KEY49_RETRY_POLL_INTERVAL`):

1. Busca documentos con `status = RETRY` y `next_retry_at <= now()`.
2. Para cada documento:
   - Si `retryCount >= maxRetries` (6): transiciona a **FAILED** con mensaje "Max retries exhausted".
   - Si no: crea un `OutboxEvent` con el tipo de evento apropiado (`doc.send` o `doc.authorize` según la etapa que falló).
3. El OutboxPoller (500ms) publica el evento a RabbitMQ y el consumer lo reintenta.

---

## Máquina de estados

Cada documento tiene un estado (`status`) que solo puede cambiar mediante transiciones validadas. El método `DocumentStatus.canTransitionTo()` previene transiciones inválidas.

### Diagrama de estados

```
                    ┌─────────┐
                    │ CREATED │
                    └────┬────┘
                         │ (SignConsumer: XML + firma)
                         ▼
                    ┌─────────┐
              ┌─────│ SIGNED  │─────┐
              │     └────┬────┘     │
              │          │          │
              │          │ (SendConsumer: SOAP Recepción)
              │          ▼          │
              │     ┌─────────┐    │
              │     │  SENT   │    │         Error de negocio SRI
              │     └────┬────┘    │    ┌─────────────────────────┐
              │          │         │    │                         │
              │          │         │    ▼                         │
              │          ▼         │  ┌──────────┐               │
              │     ┌──────────┐  │  │ REJECTED │ (terminal      │
              │     │ RECEIVED │──┘  │          │  reciclable)   │
              │     └────┬─────┘     └──────────┘               │
              │          │                                       │
              │          │ (AuthorizeConsumer: SOAP Autorización) │
              │          ▼                                       │
              │     ┌────────────┐                               │
              │     │ AUTHORIZED │                               │
              │     └──┬──────┬──┘                               │
              │        │      │                                  │
              │        │      │ (anulación local)                │
              │        │      ▼                                  │
              │        │  ┌────────┐                             │
              │        │  │ VOIDED │ (terminal absoluto)         │
              │        │  └────────┘                             │
              │        │                                         │
              │        │ (NotifyConsumer: RIDE + email + webhook) │
              │        ▼                                         │
              │   ┌──────────┐                                   │
              │   │ NOTIFIED │──────▶ VOIDED (anulación local)   │
              │   └──────────┘                                   │
              │                                                  │
              │     Error de infraestructura                      │
              │          │                                       │
              │          ▼                                       │
              │     ┌─────────┐     reintentos agotados          │
              └────▶│  RETRY  │────────────────▶ ┌────────┐     │
                    └─────────┘                  │ FAILED │     │
                         ▲                       │        │◀────┘
                         │                       └────────┘
                         │                     (terminal reciclable)
                    RetryPoller
                    (cada 5s)
```

### Tabla de transiciones válidas

| Estado actual | → Estado destino | Trigger                                                           | Responsable       |
| ------------- | ---------------- | ----------------------------------------------------------------- | ----------------- |
| CREATED       | SIGNED           | XML generado, clave de acceso calculada, firma XAdES-BES aplicada | SignConsumer      |
| CREATED       | FAILED           | Error irrecuperable en generación XML o firma                     | SignConsumer      |
| SIGNED        | SENT             | XML enviado al SRI vía SOAP Recepción                             | SendConsumer      |
| SIGNED        | RETRY            | Error de infra al enviar (timeout, conexión, circuit breaker)     | SendConsumer      |
| SIGNED        | REJECTED         | SRI devuelve DEVUELTA con error de negocio                        | SendConsumer      |
| SENT          | RECEIVED         | SRI confirma RECIBIDA                                             | SendConsumer      |
| RECEIVED      | AUTHORIZED       | SRI devuelve AUTORIZADO                                           | AuthorizeConsumer |
| RECEIVED      | REJECTED         | SRI devuelve NO AUTORIZADO                                        | AuthorizeConsumer |
| RECEIVED      | RETRY            | Error de infra al consultar autorización                          | AuthorizeConsumer |
| AUTHORIZED    | NOTIFIED         | RIDE generado + email enviado + webhook disparado                 | NotifyConsumer    |
| AUTHORIZED    | VOIDED           | Anulación local solicitada por el tenant                          | API REST          |
| NOTIFIED      | VOIDED           | Anulación local solicitada por el tenant                          | API REST          |
| RETRY         | SIGNED           | Re-procesamiento (vuelve a firmar si necesario)                   | RetryPoller       |
| RETRY         | SENT             | Re-envío al SRI                                                   | RetryPoller       |
| RETRY         | FAILED           | Reintentos agotados (máximo 6)                                    | RetryPoller       |
| REJECTED      | CREATED          | Reciclaje: cliente reenvía con datos corregidos                   | API REST          |
| FAILED        | CREATED          | Reciclaje: cliente reenvía con datos corregidos                   | API REST          |
| VOIDED        | —                | Estado terminal absoluto, sin transiciones                        | —                 |

### Reciclaje de documentos

Cuando un documento está en estado REJECTED o FAILED, el integrador puede reenviar el mismo comprobante (misma combinación de tipo + establecimiento + punto emisión + secuencial). El sistema:

1. Resetea el estado a CREATED.
2. Limpia campos de procesamiento (access_key, authorization_number, errores, rutas XML).
3. Actualiza el `request_payload` con los nuevos datos.
4. Crea un nuevo evento outbox para iniciar el procesamiento.

Si el documento existente está en un estado **activo** (CREATED, SIGNED, SENT, RECEIVED, RETRY) o **completado** (AUTHORIZED, NOTIFIED, VOIDED), el sistema retorna HTTP 409.

---

## Circuit breaker

Key49 usa MicroProfile Fault Tolerance (`@CircuitBreaker`) para proteger las llamadas a servicios externos: SRI (SOAP) y MinIO (S3).

### Comportamiento del circuit breaker

```
       ┌──────────┐   5+ fallos en 10 requests   ┌──────────┐
       │ CERRADO  │─────────────────────────────▶│ ABIERTO  │
       │ (normal) │                               │(rechaza) │
       └──────────┘                               └────┬─────┘
            ▲                                          │
            │                                     30 segundos
            │ 3 éxitos consecutivos                    │
            │                                          ▼
            │                                    ┌───────────┐
            └────────────────────────────────────│SEMI-ABIERTO│
                                                 │(prueba 1) │
                                                 └───────────┘
```

### Parámetros por servicio

| Servicio             | Volume Threshold | Failure Ratio | Delay | Success Threshold | Timeout     |
| -------------------- | ---------------- | ------------- | ----- | ----------------- | ----------- |
| **SRI Recepción**    | 10 requests      | 50%           | 30s   | 3 éxitos          | 3s          |
| **SRI Autorización** | 10 requests      | 50%           | 30s   | 3 éxitos          | 5s          |
| **MinIO**            | 10 requests      | 50%           | 30s   | 3 éxitos          | 30s (write) |

### ¿Qué pasa cuando el circuito se abre?

1. Todas las llamadas al servicio afectado lanzan `CircuitBreakerOpenException` inmediatamente (sin intentar la conexión).
2. Los consumers capturan esta excepción y transicionan el documento a **RETRY** con backoff.
3. Después de 30 segundos, el circuito pasa a **semi-abierto** y permite 1 request de prueba.
4. Si 3 requests consecutivos tienen éxito, el circuito se **cierra** (operación normal).
5. Si falla de nuevo, vuelve a **abierto** por otros 30 segundos.

### Implicación práctica

Con el circuito abierto:

- Los documentos nuevos siguen entrando (se crean con estado CREATED).
- El SignConsumer sigue funcionando (no depende del SRI).
- Los documentos firmados (SIGNED) que intentan enviarse van a RETRY automáticamente.
- Cuando el SRI se recupera y el circuito se cierra, el RetryPoller re-encola los documentos y se procesan normalmente.
- Si los reintentos se agotan (6 intentos, ~10 min), el documento pasa a FAILED.

---

## Redis — caché y resiliencia

Redis funciona como **caché y session store**. No almacena datos críticos — si Redis cae, el sistema sigue funcionando con degradación mínima.

### Qué se almacena en Redis

| Funcionalidad         | Clave                                 | TTL          | Descripción                                                       |
| --------------------- | ------------------------------------- | ------------ | ----------------------------------------------------------------- |
| **API key cache**     | `key49:apikey:{sha256_hash}`          | 300s (5 min) | Datos del API key + tenant para evitar query a BD en cada request |
| **Sesiones portal**   | `session:{session_id}`                | 30 min       | tenant_id, schema_name, legal_name del usuario logueado           |
| **Rate limiting**     | `ratelimit:{api_key_prefix}:{window}` | 60s          | Contador sliding window por ventana de 1 minuto                   |
| **Estado de alertas** | `key49:alert:{rule}:{dimension}`      | 7 días       | Flag on/off para evitar alertas duplicadas                        |

### Comportamiento si Redis no está disponible

| Funcionalidad         | Fallback                           | Impacto                                  |
| --------------------- | ---------------------------------- | ---------------------------------------- |
| **API key cache**     | Consulta directa a PostgreSQL      | +1-2ms por request (mínimo)              |
| **Sesiones portal**   | Se pierden las sesiones activas    | Usuarios deben re-autenticarse           |
| **Rate limiting**     | Se desactiva (permisivo)           | Sin control de tasa temporal             |
| **Estado de alertas** | Alertas pueden enviarse duplicadas | Alertas repetidas hasta que Redis vuelva |

La implementación usa un patrón de **degradación graceful**: cada operación Redis está envuelta en try/catch. Si Redis falla, se registra un warning y se usa el fallback.

```
Flujo normal:    Request → Redis (HIT) → Respuesta
Cache miss:      Request → Redis (MISS) → PostgreSQL → Guardar en Redis → Respuesta
Redis caído:     Request → Redis (ERROR, log warning) → PostgreSQL → Respuesta
```

---

## MinIO — almacenamiento de artefactos

MinIO almacena los artefactos generados durante el procesamiento de cada comprobante: XMLs y RIDEs (PDF).

### Estructura de rutas

```
{bucket}/{tenant_id}/{año}/{mes}/{tipo_doc}/{clave_acceso}/{archivo}
```

Ejemplo:

```
key49-documents/
  tenant_abc123/
    2026/
      04/
        01/                                          ← tipo doc (factura)
          2506202601099271531200110010020000000011234567813/
            signed.xml                                ← XML firmado
            authorized.xml                            ← XML autorizado (con número autorización)
            ride.pdf                                  ← RIDE (representación impresa)
```

### Artefactos por documento

| Archivo          | Generado en             | Tamaño típico |
| ---------------- | ----------------------- | ------------- |
| `signed.xml`     | NotifyConsumer (fase 2) | 5–50 KB       |
| `authorized.xml` | NotifyConsumer (fase 2) | 5–50 KB       |
| `ride.pdf`       | NotifyConsumer (fase 2) | 50–200 KB     |

### Circuit breaker en MinIO

MinIO tiene su propio circuit breaker (10 requests / 50% fallos / 30s delay) en las operaciones `store()` y `retrieve()`.

### Timeouts de MinIO

| Operación | Timeout     |
| --------- | ----------- |
| Conexión  | 5 segundos  |
| Escritura | 30 segundos |
| Lectura   | 15 segundos |

### ¿Qué pasa si MinIO está caído?

1. El NotifyConsumer falla al subir los artefactos.
2. El documento queda en estado AUTHORIZED (no avanza a NOTIFIED).
3. El consumer hace retry con backoff exponencial.
4. Cuando MinIO se recupera, los documentos se procesan normalmente.
5. El documento ya está **autorizado ante el SRI** — los artefactos son una copia local.

### Retención

Los artefactos se mantienen **7 años** (requisito legal SRI para documentos tributarios). La política de retención se aplica a nivel de bucket en MinIO.

---

## PgBouncer — pool de conexiones

PgBouncer se coloca entre Key49 y PostgreSQL para gestionar eficientemente las conexiones al servidor de base de datos.

### Arquitectura de conexiones

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   Key49      │     │  PgBouncer   │     │  PostgreSQL  │
│              │     │              │     │              │
│  Agroal Pool │────▶│  Transaction │────▶│  Conexiones  │
│  (max=20)    │     │  Mode        │     │  reales      │
│              │     │  (pool=50)   │     │              │
└──────────────┘     └──────────────┘     └──────────────┘
      app:8080          pgb:6432             pg:5432
```

### Modo de operación: Transaction

PgBouncer opera en **modo transaction**: cada transacción de la aplicación obtiene una conexión del pool del servidor, la usa, y la devuelve al finalizar (commit/rollback). Esto permite que muchas transacciones compartan un número reducido de conexiones reales.

### Compatibilidad con multi-tenancy

La clave de la compatibilidad es `SET LOCAL search_path`:

- `SET LOCAL` aplica el cambio **solo durante la transacción actual**.
- Al hacer commit o rollback, el `search_path` se resetea automáticamente.
- Esto evita que una transacción de un tenant "contamine" la siguiente transacción que reutilice la misma conexión.

```sql
-- Dentro de cada transacción:
SET LOCAL search_path TO 'tenant_abc123', public;
-- ... queries al esquema del tenant ...
COMMIT;  -- search_path se resetea automáticamente
```

Si se usara `SET search_path` (sin LOCAL), el cambio persistiría en la conexión y podría afectar la siguiente transacción — esto sería un **problema grave de seguridad** con PgBouncer en modo transaction.

### Sizing del pool

| Componente       | Parámetro         | Valor default | Variable de entorno |
| ---------------- | ----------------- | ------------- | ------------------- |
| **Agroal** (app) | pool max          | 20            | `KEY49_DB_POOL_MAX` |
| **PgBouncer**    | default_pool_size | 50            | Config PgBouncer    |
| **PostgreSQL**   | max_connections   | 100           | Config PostgreSQL   |

Regla: `Agroal max` ≤ `PgBouncer pool_size` ≤ `PostgreSQL max_connections`.

Sin PgBouncer, el pool de Agroal debería ser: `min(num_tenants × 2 + 10, 50)`.

---

## Outbox pattern

El Outbox Pattern garantiza **entrega exactamente-una-vez** (exactly-once delivery) de eventos del dominio a RabbitMQ, incluso ante fallos parciales.

### Problema que resuelve

Sin outbox, después de persistir un documento podría fallar la publicación a RabbitMQ (o viceversa), dejando el sistema en un estado inconsistente:

```
❌ Sin outbox:
1. INSERT document (CREATED)     ← OK
2. Publicar a RabbitMQ           ← FALLA → documento queda en CREATED sin procesar
```

### Solución con outbox

```
✅ Con outbox:
1. BEGIN TRANSACTION
2. INSERT document (CREATED)
3. INSERT outbox_event (doc.sign, published=false)
4. COMMIT                        ← Ambos o ninguno (atomicidad ACID)

--- OutboxPoller (cada 500ms) ---
5. SELECT FROM outbox WHERE published=false LIMIT 50 FOR UPDATE SKIP LOCKED
6. Publicar a RabbitMQ
7. UPDATE outbox SET published=true
```

### Componentes del outbox

#### OutboxPoller (intervalo: 500ms)

1. Obtiene la lista de tenants activos.
2. Para cada tenant, ejecuta `SET search_path` y consulta la tabla `outbox`.
3. Usa `FOR UPDATE SKIP LOCKED` para soportar múltiples instancias de Key49 sin conflictos de locks.
4. Para cada evento no publicado:
   - Lo enruta al producer correcto vía `OutboxEventRouter`.
   - Marca como publicado (`published = true`, `published_at = now()`).
5. Si falla la publicación: el evento se reintenta en el siguiente ciclo (500ms después).
6. Si falla el UPDATE (ya se publicó pero no se marcó): el consumer es **idempotente** — procesar el mismo mensaje dos veces no genera efectos secundarios.

#### OutboxEventRouter

Enruta eventos al producer correcto según `event_type`:

| event_type      | Producer                                  | Cola destino      |
| --------------- | ----------------------------------------- | ----------------- |
| `doc.sign`      | `DocumentEventProducer.sendToSign()`      | `key49.sign`      |
| `doc.send`      | `DocumentEventProducer.sendToSend()`      | `key49.send`      |
| `doc.authorize` | `DocumentEventProducer.sendToAuthorize()` | `key49.authorize` |
| `doc.notify`    | `DocumentEventProducer.sendToNotify()`    | `key49.notify`    |

#### OutboxCleanup (2:00 AM Ecuador)

Job nocturno que elimina eventos publicados con más de 7 días de antigüedad:

```sql
DELETE FROM outbox WHERE published = true AND published_at < now() - interval '7 days';
```

Se ejecuta a las 2:00 AM hora Ecuador (horario de bajo tráfico) para minimizar carga en la BD.

### Configuración

```bash
KEY49_OUTBOX_POLL_INTERVAL=500ms    # Intervalo del poller
KEY49_OUTBOX_BATCH_SIZE=50          # Eventos por ciclo por tenant
```

### Métricas

| Métrica                      | Tipo    | Descripción                       |
| ---------------------------- | ------- | --------------------------------- |
| `key49.outbox.events.polled` | Counter | Total de eventos publicados       |
| `key49.outbox.poll.duration` | Timer   | Duración de cada ciclo del poller |

---

## Webhooks

Key49 notifica a los integradores sobre cambios de estado de sus documentos mediante webhooks HTTP POST firmados con HMAC-SHA256.

### Flujo de entrega

```
1. Documento cambia de estado (ej: AUTHORIZED)
2. NotifyConsumer llama a WebhookDispatcher.dispatch()
3. WebhookDispatcher:
   a. Valida la URL del tenant (SSRF prevention)
   b. Construye el payload JSON
   c. Calcula firma HMAC-SHA256 con el webhook_secret del tenant
   d. Envía POST con headers de firma
   e. Registra el resultado en webhook_deliveries
```

### Headers enviados

```http
POST https://integrador.example.com/webhook HTTP/1.1
Content-Type: application/json
X-Key49-Signature: sha256=a1b2c3d4e5f6...  (64 caracteres hex)
X-Key49-Event: document.authorized
X-Key49-Delivery: 550e8400-e29b-41d4-a716-446655440000
```

### Firma HMAC-SHA256

El integrador verifica la autenticidad del webhook comparando la firma:

```
firma = HMAC-SHA256(body_json, webhook_secret)
header = "sha256=" + hex(firma)
```

El `webhook_secret` se genera al crear el tenant y se comparte una sola vez.

### Validación SSRF

`WebhookUrlValidator` previene que un tenant configure URLs maliciosas:

- **Bloqueados**: `localhost`, `*.local`, `*.internal`, `[::1]`, `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.169.254` (metadata de nube).
- **Requerido en producción**: HTTPS obligatorio (configurable).

### Reintentos de webhook

Si el integrador no responde o retorna error (HTTP != 2xx):

```
Intento 1: inmediato
Intento 2: espera  10 segundos
Intento 3: espera  60 segundos  (1 min)
Intento 4: espera 300 segundos  (5 min)
───────────────────────────────
Máximo: 3 reintentos, luego status="failed"
```

Cada intento se registra en la tabla `webhook_deliveries` con: `response_status`, `response_body`, `duration_ms`, `attempt`, `next_attempt_at`.

### Timeouts de webhook

| Parámetro       | Valor       |
| --------------- | ----------- |
| Connect timeout | 5 segundos  |
| Read timeout    | 10 segundos |

### Tipos de evento

| Evento                 | Cuándo se dispara                    |
| ---------------------- | ------------------------------------ |
| `document.authorized`  | Documento autorizado por el SRI      |
| `document.rejected`    | Documento rechazado por el SRI       |
| `document.failed`      | Reintentos agotados                  |
| `certificate.expiring` | Certificado vence en < 30 días       |
| `certificate.expired`  | Certificado ya venció                |
| `system.incident`      | Servicio SRI pasó de UP a DOWN       |
| `system.resolved`      | Servicio SRI se recuperó (DOWN a UP) |
| `system.maintenance`   | Ventana de mantenimiento programado  |

---

## Idempotencia

Key49 soporta idempotencia en todas las operaciones POST mediante el header `X-Idempotency-Key`.

### Flujo

```
1. Integrador envía POST /v1/invoices con X-Idempotency-Key: "mi-clave-unica"
2. Key49 busca en la BD un documento con idempotency_key = "mi-clave-unica"
3. Si existe → retorna el documento existente (HTTP 200)
4. Si no existe → procesa normalmente, guarda la clave en el documento
```

### Implementación

La clave de idempotencia se almacena como campo `idempotency_key` en la tabla `documents`. La búsqueda se realiza por query directo a PostgreSQL (no Redis).

### Escenarios

| Escenario                                        | Resultado                                           |
| ------------------------------------------------ | --------------------------------------------------- |
| Primera vez con X-Idempotency-Key                | Se procesa normalmente, HTTP 202                    |
| Mismo X-Idempotency-Key por segunda vez          | Se retorna el documento existente, HTTP 200         |
| Sin header X-Idempotency-Key                     | Se procesa normalmente (sin protección)             |
| Mismo contenido pero diferente X-Idempotency-Key | Se crea un nuevo documento (son requests distintos) |

### Unicidad del documento

Complementariamente, la tabla `documents` tiene un constraint UNIQUE sobre `(document_type, establishment, issue_point, sequence_number)`. Esto previene duplicados incluso sin X-Idempotency-Key. Si el documento existe en estado REJECTED/FAILED, se recicla; si está activo, retorna HTTP 409.

---

## Resiliencia ante caídas

### Tabla de escenarios de fallo

| Componente caído | Impacto                                                                                                                        | Recuperación                                                                                                                |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| **PostgreSQL**   | **Sistema detenido**. No se pueden recibir requests ni procesar documentos. Health check falla.                                | Al recuperarse, el sistema retoma operación normal. Los mensajes en RabbitMQ se procesan. No se pierden datos.              |
| **RabbitMQ**     | Los consumers dejan de recibir mensajes. Los documentos se crean (CREATED) pero no avanzan. El OutboxPoller falla al publicar. | Al recuperarse, el OutboxPoller publica los eventos pendientes. Los documentos se procesan en orden.                        |
| **Redis**        | Degradación menor. API key cache se bypasea (consulta BD). Sesiones del portal se pierden. Rate limiting se desactiva.         | Al recuperarse, el caché se repuebla automáticamente. Usuarios del portal deben re-autenticarse.                            |
| **MinIO**        | Los documentos se autorizan pero no se generan artefactos (XML/PDF). NotifyConsumer falla y hace retry.                        | Al recuperarse, los documentos en RETRY se procesan. Los artefactos se generan normalmente.                                 |
| **SRI (SOAP)**   | El circuit breaker se abre. Los documentos firmados van a RETRY. Los nuevos documentos se crean y firman normalmente.          | Al recuperarse, el circuito pasa a semi-abierto. Tras 3 éxitos, se cierra. RetryPoller re-encola los documentos pendientes. |
| **Email (SMTP)** | Los documentos llegan a NOTIFIED pero sin email. El `emailStatus` queda como "failed".                                         | El email no es bloqueante. El documento ya está notificado por webhook. Se puede reenviar manualmente.                      |

### Principios de diseño

1. **PostgreSQL es el único punto crítico**. Si cae, todo se detiene. Los demás servicios tienen fallback o retry.
2. **RabbitMQ es recuperable**: los eventos pendientes se almacenan en la tabla `outbox`. Nada se pierde aunque RabbitMQ esté caído por horas.
3. **Redis es prescindible**: el sistema funciona sin Redis con degradación mínima (más queries a BD, sin sesiones portal).
4. **MinIO es prescindible temporalmente**: los documentos se autorizan ante el SRI sin necesitar MinIO. Los artefactos se generan después.
5. **SRI caído = retry automático**: el circuit breaker protege al sistema de saturarse con timeouts. El backoff exponencial esparce los reintentos.

---

## Apagado graceful

Cuando Key49 recibe señal de apagado (SIGTERM), inicia un proceso ordenado de 30 segundos:

### Secuencia de apagado

```
1. SIGTERM recibido
2. Quarkus deja de aceptar nuevos requests HTTP
3. GracefulShutdownObserver registra mensajes in-flight por consumer
4. Los consumers actuales terminan de procesar sus mensajes (hasta 30s)
5. Si un mensaje no termina en 30s:
   - RabbitMQ lo detecta (connection closed, no ACK)
   - RabbitMQ re-encola el mensaje (basic.nack con requeue=true)
   - Otro consumer (o la misma instancia al reiniciar) lo procesará
6. Quarkus cierra conexiones a BD, Redis, MinIO, RabbitMQ
```

### Configuración

```properties
quarkus.shutdown.timeout=30s
```

### Implicaciones para despliegues

- **Rolling update**: si se despliega una nueva versión, la instancia vieja tiene 30s para completar mensajes en vuelo.
- **No se pierden mensajes**: RabbitMQ re-encola los no confirmados.
- **No se pierden documentos**: el estado se persiste en BD antes de cada transición.
- **Idempotencia de consumers**: si un mensaje se procesa parcialmente y se re-encola, el consumer detecta el estado actual del documento y actúa en consecuencia.

---

## Gestión de tenants y planes

### Ciclo de vida de un tenant

```
Autoregistro (portal web)
  → Tenant creado (status=pending, plan=DEMO)
  → Email de verificación enviado (token Redis, TTL 24h)
  → Usuario verifica email → status=active, email_verified=true
  → Uso normal (emitir documentos, consultar, etc.)
  → Plan vence:
      - ENTERPRISE: auto-renovación automática
      - Otros: status=expired (no puede emitir documentos)
  → Solicita renovación → Admin aprueba → status=active, nuevo plan
```

### Job de expiración de planes

`PlanExpirationService` ejecuta un cron diario a las **00:05 ECT**:

- Busca tenants con `status = 'active'` y `plan_expires_at < now`.
- Tenants ENTERPRISE: auto-renovación (nuevo período 30 días, cuota reseteada, registro en `plan_renewals`).
- Otros planes: cambia `status = 'expired'`, invalida caché Redis, dispara alerta webhook.

**Monitoreo**: verificar en los logs del job:

```
Starting plan expiration check...
Plan expiration check complete: X expired, Y auto-renewed
```

### Flujo de renovación

1. **Solicitud** (portal): el tenant accede a `/portal/plan`, selecciona nuevo plan, sube comprobante de pago.
2. **Revisión** (admin): listar pendientes con `GET /v1/admin/renewals?status=pending`.
3. **Aprobación**: `POST /v1/admin/renewals/{id}/approve` — actualiza plan, cuota, rate limits, resetea documentos usados.
4. **Rechazo**: `POST /v1/admin/renewals/{id}/reject` con `{"reason": "..."}`.

### Administración de renovaciones

Los endpoints de admin requieren header `X-Admin-Token`:

```bash
# Listar renovaciones pendientes
curl -s http://localhost:8080/v1/admin/renewals?status=pending \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .

# Aprobar renovación
curl -s -X POST http://localhost:8080/v1/admin/renewals/{id}/approve \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .

# Rechazar renovación
curl -s -X POST http://localhost:8080/v1/admin/renewals/{id}/reject \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Comprobante inválido"}' | jq .
```

### Verificación de email

- Token almacenado en Redis con prefijo `email-verify:{token}` (TTL 24h).
- Rate limit: 3 solicitudes por email por hora.
- Endpoint público: `GET /portal/verify?token=...`.
- Al verificar exitosamente: `email_verified = true`, `status = 'active'`.

### Rate limits por plan

Al aprobar una renovación, los rate limits del tenant se ajustan automáticamente:

| Plan       | Write RPM | Read RPM |
| ---------- | --------- | -------- |
| DEMO       | 10        | 30       |
| STARTER    | 30        | 100      |
| BUSINESS   | 60        | 200      |
| ENTERPRISE | 200       | 600      |

Para más detalles de planes, ver `docs/PLANS.md`.

# Arquitectura Técnica — Key49

## Decisiones Arquitectónicas (ADR)

### ADR-001: Java 25 + Quarkus sobre Spring Boot / Node.js / Go

**Contexto**: Necesitamos firmar XML con XAdES-BES usando certificados .p12, consumir servicios SOAP legacy del SRI, y procesar colas de mensajes con alto throughput.

**Decisión**: Java 25 LTS + Quarkus 3.x

**Razones**:

- Java tiene el ecosistema más maduro para firma XAdES-BES (Apache Santuario, BouncyCastle, JCA)
- Quarkus consume ~17MB RAM vs ~100MB de Spring Boot para la misma funcionalidad
- Quarkus tiene integración nativa con SmallRye Reactive Messaging para RabbitMQ
- Soporte nativo para clientes SOAP via Apache CXF / Jakarta XML WS
- Compilación nativa con GraalVM disponible para futuro (containers ultra-livianos)
- El equipo ya tiene experiencia con Quarkus (Key49 previo)

**Consecuencias**: Mayor complejidad inicial que Node.js, pero eliminamos el riesgo técnico de la firma digital.

---

### ADR-002: RabbitMQ sobre Kafka / Redis Streams

**Contexto**: Necesitamos colas con reintentos, dead letter queues, prioridades, y TTL por mensaje.

**Decisión**: RabbitMQ 3.13+

**Razones**:

- Dead Letter Exchanges nativos (esencial para reintentos con backoff)
- TTL por mensaje (permite backoff exponencial sin código adicional)
- Exchange routing por tipo de documento
- Ya desplegado en la infraestructura existente
- Menor complejidad operativa que Kafka para nuestro volumen
- Plugin de delayed message exchange para scheduling de reintentos

**Consecuencias**: Menor throughput máximo que Kafka (~50K msg/s vs ~1M msg/s), pero más que suficiente para nuestro caso de uso.

---

### ADR-003: Multi-tenancy por esquema de PostgreSQL (schema-per-tenant)

**Contexto**: Cada tenant tiene su RUC, certificado, y comprobantes. Necesitamos aislamiento de datos con garantías fuertes de seguridad y compliance para datos financieros.

**Decisión**: Schema-per-tenant. Un esquema de PostgreSQL por cada tenant (`tenant_{uuid_short}`) que contiene todas las tablas de negocio. El esquema `public` contiene únicamente las tablas de administración (`tenants`, `api_keys`).

**Razones**:

- **Aislamiento físico real**: cada tenant tiene su propio conjunto de tablas. Imposible filtrar mal un WHERE y exponer datos ajenos.
- **Datos financieros sensibles**: facturas, retenciones, y certificados digitales requieren aislamiento fuerte. Un bug de código no puede filtrar datos cross-tenant.
- **Backup/restore granular**: se puede respaldar o restaurar el esquema de un tenant sin afectar a otros.
- **Performance predecible**: índices y estadísticas por esquema, sin contención entre tenants.
- **Cumplimiento normativo**: facilita auditorías y requerimientos legales de aislamiento de datos tributarios.
- **Volumen de tenants bajo**: Key49 apunta a decenas/cientos de tenants (empresas emisoras), no miles. El overhead de N esquemas es asumible.

**Mecanismo de aislamiento**:

1. Al autenticarse con API Key, se identifica el `tenant_id` y su `schema_name` desde `public.tenants`.
2. Se ejecuta `SET search_path TO 'tenant_{uuid_short}', public;` al inicio de cada request/transacción.
3. Hibernate Reactive con Panache usa la estrategia `SCHEMA` para resolver la tabla correcta.
4. Las tablas del esquema tenant NO tienen columna `tenant_id` — el aislamiento lo garantiza el esquema.

**Gestión de esquemas y migraciones**:

- **NO hay migraciones automáticas** (Flyway/Liquibase NO se ejecutan al arrancar la aplicación).
- **Todas las migraciones son manuales**: el DBA ejecuta los scripts SQL directamente.
- **Crear un tenant = solo INSERT** en `public.tenants` con el campo `schema_name`. La creación del esquema PostgreSQL y sus tablas se realiza manualmente por el DBA, NO por la aplicación.
- Los scripts SQL de referencia se mantienen en `db/migrations/` para que el DBA los ejecute manualmente.

**Estructura de esquemas**:

```
PostgreSQL Database: key49
├── public                        # Esquema público (administración)
│   ├── tenants                   # Registro de tenants
│   └── api_keys                  # Claves de API
├── tenant_abc123                 # Esquema del tenant ABC
│   ├── documents
│   ├── outbox
│   ├── webhook_deliveries
│   └── audit_log
├── tenant_def456                 # Esquema del tenant DEF
│   ├── documents
│   └── ...
```

**Consecuencias**:

- El filtro de autenticación debe ejecutar `SET search_path` en cada request.
- Crear un nuevo tenant requiere intervención manual del DBA (crear esquema + ejecutar scripts de tablas).
- Las migraciones de estructura (ALTER TABLE, nuevas columnas) deben aplicarse manualmente a cada esquema de tenant.
- Queries cross-tenant para reportes admin requieren queries explícitas con schema qualification (`tenant_abc.documents`).

---

### ADR-004: Almacenamiento de certificados .p12

**Contexto**: Los certificados .p12 contienen la clave privada del emisor. Son el activo más sensible del sistema.

**Decisión**: Almacenar cifrados con AES-256-GCM en PostgreSQL (columna bytea). La clave de cifrado se almacena como variable de entorno (o futuro vault).

**Razones**:

- Más simple que montar un vault (HashiCorp Vault) en fase inicial
- Cifrado at-rest con clave maestra por entorno
- Respaldo incluido en backup de PostgreSQL
- Migración futura a vault sin cambio de schema

**Consecuencias**: La clave maestra debe protegerse rigurosamente. Rotación de certificados requiere re-cifrado.

---

### ADR-005: Procesamiento asíncrono con respuesta síncrona parcial

**Contexto**: Desde enero 2026 el SRI exige transmisión en tiempo real. Pero el SRI puede tardar segundos o estar caído.

**Decisión**: Patrón híbrido:

1. La API recibe el request, valida, genera XML, firma y envía al SRI sincrónicamente (fast path)
2. Si el SRI responde RECIBIDA en < 3s, el polling de autorización va a la cola
3. Si el SRI no responde en 3s o hay error de conexión, el envío completo va a la cola de retry
4. El cliente siempre recibe un 202 Accepted con el ID y la clave de acceso
5. El estado final llega por webhook o se consulta por polling GET

**Razones**:

- Cumple con "tiempo real" del SRI (el envío es inmediato)
- No bloquea al integrador indefinidamente
- Resiliente a caídas del SRI
- El webhook cierra el ciclo asíncrono

**Consecuencias**: El integrador debe implementar webhook o polling para conocer el estado final.

---

### ADR-006: JSON como interfaz principal + XML raw como canal avanzado

**Contexto**: Key49 necesita definir qué acepta como dato de entrada: ¿un JSON con los datos del documento para que Key49 genere el XML internamente, o un XML ya formado por el integrador?

**Decisión**: Doble interfaz. JSON como canal principal (Fase 1) y XML raw como canal avanzado (Fase 2).

```
POST /v1/invoices          → JSON (Key49 genera XML)     — 95% de clientes
POST /v1/documents/raw     → XML pre-armado por cliente  — 5% avanzados
```

**Razones**:

- **Propuesta de valor diferenciada**: si solo firmamos y enviamos, somos commoditizables. Generar el XML conforme al SRI es el verdadero dolor del desarrollador ecuatoriano.
- **Menor soporte**: Key49 controla el XML generado → errores de estructura (código SRI 52) casi desaparecen. Menos tickets, menor costo de soporte.
- **Absorción de cambios SRI**: cuando el SRI actualice XSD o ficha técnica, Key49 actualiza una vez y todos los clientes se benefician sin cambiar código.
- **No perder clientes avanzados**: ERPs grandes que ya generan su propio XML pueden migrar a Key49 sin reescribir su generación XML.
- **El endpoint raw es de bajo esfuerzo**: ya tenemos validador XSD (T-008), firmador (T-009), y clientes SOAP (T-011/T-012). Solo se conectan sin el builder XML.

**Interfaz JSON (principal)**: el integrador envía un JSON simple con los datos del documento. Key49 se encarga de:

1. Validar los datos de entrada
2. Generar la clave de acceso (49 dígitos, módulo 11)
3. Construir el XML conforme al XSD vigente del SRI
4. Validar contra XSD
5. Firmar con XAdES-BES
6. Enviar por SOAP al SRI
7. Generar RIDE, almacenar, notificar

**Interfaz XML raw (avanzada)**: el integrador envía un XML ya conformado. Key49 se encarga de:

1. Validar contra XSD correspondiente
2. Extraer metadatos del XML (tipo documento, receptor, totales) para persistir en BD
3. Verificar o generar clave de acceso
4. Firmar con XAdES-BES
5. Enviar por SOAP al SRI
6. Generar RIDE, almacenar, notificar

**Consecuencias**:

- La tabla `documents` necesita una columna `request_origin` para distinguir el canal de entrada ('JSON' o 'XML_RAW').
- El XML Raw requiere un parser que extraiga datos básicos del XML (receptor, totales) para persistir en la tabla `documents`.
- **No se almacenan ítems ni pagos en tablas separadas**: estos datos se preservan en `request_payload` (JSON) o `original_xml` (XML raw) y en los XML almacenados en MinIO. Para generar el RIDE se parsea el XML autorizado.
- Se necesita decidir si Key49 genera la clave de acceso o acepta la del cliente (decisión: Key49 siempre genera la clave de acceso para garantizar unicidad y correcto módulo 11).
- **El secuencial (`sequence_number`) lo proporciona el cliente** en su request. Key49 no gestiona secuencias.

---

### ADR-007: Portal web dentro del paquete `api` (Qute + HTMX + Pico CSS)

**Contexto**: Los clientes necesitan una forma visual de consultar el estado de sus documentos electrónicos sin depender exclusivamente de la API REST o webhooks.

**Decisión**: Portal web de solo lectura integrado en el paquete `api` bajo el path `/portal/`. Server-side rendering con Qute, Pico CSS para estilos y HTMX para interactividad mínima.

**Razones**:

- **Sin dependencias frontend**: no requiere npm, webpack, node_modules ni build de frontend. Cero complejidad adicional.
- **Qute ya está en el proyecto**: se usa para templates de RIDE y email. Reutilizar la misma herramienta.
- **Pico CSS**: un solo archivo CSS (~10KB) que estiliza HTML semántico sin clases. Solo escribir `<table>`, `<form>`, `<button>`.
- **HTMX**: un solo archivo JS (~14KB) para polling de estado y actualización parcial sin escribir JavaScript.
- **Dentro del paquete `api`**: son ~3 pantallas de lectura. No justifica un proyecto separado.
- **Solo lectura**: el portal no crea ni modifica documentos. Solo visualiza estado, datos resumen, y ofrece descargas de XML/RIDE.

**Pantallas**:

| Ruta                     | Pantalla  | Función                                                      |
| ------------------------ | --------- | ------------------------------------------------------------ |
| `/portal/login`          | Login     | Autenticación con API key del tenant                         |
| `/portal/`               | Dashboard | Tabla de documentos con filtros (fecha, estado) + paginación |
| `/portal/documents/{id}` | Detalle   | Estado, timeline de procesamiento, links de descarga         |

**Consecuencias**:

- El paquete `auracore.key49.api.portal` contiene los endpoints Qute y el filtro de autenticación por sesión.
- Los templates viven en `src/main/resources/templates/portal/`.
- Los assets estáticos (`pico.min.css`, `htmx.min.js`) en `src/main/resources/META-INF/resources/`.
- La autenticación del portal es por sesión/cookie (distinta a Bearer token de la API REST).

---

## Estructura del Proyecto

**Módulo único Maven** (packaging `jar`). La separación lógica se logra por paquetes Java dentro de `src/main/java/auracore/key49/`:

```
key49/
├── src/main/java/auracore/key49/
│   ├── api/                          # REST endpoints + Portal web
│   │   ├── resource/                 # JAX-RS endpoints (API REST)
│   │   ├── portal/                   # Portal web (server-side rendering)
│   │   │   ├── PortalResource.java   # Endpoints Qute que renderizan HTML
│   │   │   ├── PortalAuthFilter.java # Autenticación por sesión/cookie
│   │   │   └── dto/                  # ViewModels para templates
│   │   ├── dto/                      # Request/Response DTOs
│   │   ├── mapper/                   # MapStruct mappers
│   │   ├── filter/                   # Tenant context filter, auth filter
│   │   └── exception/                # Exception mappers
│   │
│   ├── core/                         # Dominio y lógica de negocio
│   │   ├── model/                    # Entidades JPA (Document, Tenant, etc.)
│   │   ├── service/                  # Servicios de negocio
│   │   ├── repository/               # Repositorios Panache
│   │   └── event/                    # Eventos de dominio
│   │
│   ├── xml/                          # Generación y validación XML
│   │   ├── builder/                  # XML builders por tipo de documento
│   │   ├── validator/                # Validación contra XSD
│   │   ├── accesskey/                # Generador de clave de acceso (mod 11)
│   │   └── schema/                   # Carga dinámica de XSD
│   │
│   ├── signer/                       # Firma digital XAdES-BES
│   │   ├── XAdESBESSigner.java       # Implementación de firma
│   │   ├── CertificateManager.java   # Carga y gestión de .p12
│   │   └── CertificateEncryptor.java # Cifrado/descifrado AES-256-GCM
│   │
│   ├── sri/                          # Integración SOAP con SRI
│   │   ├── client/                   # Cliente SOAP (Recepción + Autorización)
│   │   ├── model/                    # Modelos de respuesta SRI
│   │   ├── parser/                   # Parser de respuestas SOAP
│   │   └── config/                   # Configuración de endpoints por ambiente
│   │
│   ├── queue/                        # Mensajería y workers
│   │   ├── producer/                 # Productores de mensajes
│   │   ├── consumer/                 # Consumidores (sign, send, authorize, notify)
│   │   ├── retry/                    # Lógica de reintentos con backoff
│   │   └── dlq/                      # Manejo de Dead Letter Queue
│   │
│   ├── ride/                         # Generación de RIDE (PDF)
│   │   ├── generator/                # Generador PDF por tipo de documento
│   │   ├── template/                 # Templates de RIDE
│   │   └── qr/                       # Generador de código QR
│   │
│   ├── notify/                       # Notificaciones
│   │   ├── email/                    # Envío de emails con RIDE + XML
│   │   ├── webhook/                  # Dispatcher de webhooks a integradores
│   │   └── template/                 # Templates de email (Qute)
│   │
│   ├── storage/                      # Almacenamiento (MinIO/S3)
│   │   ├── ObjectStorageService.java
│   │   └── RetentionPolicy.java      # Política de 7 años
│   │
│   └── admin/                        # Administración y monitoreo
│       ├── resource/                 # Endpoints admin
│       ├── metrics/                  # Métricas custom
│       └── health/                   # Health checks (SRI, RabbitMQ, DB)
│
├── src/main/resources/
│   ├── application.properties
│   ├── META-INF/openapi.yaml
│   ├── META-INF/resources/           # Assets estáticos del portal
│   │   ├── css/pico.min.css          # Pico CSS (~10KB, sin clases)
│   │   └── js/htmx.min.js           # HTMX (~14KB, interactividad mínima)
│   ├── templates/                    # Templates Qute
│   │   ├── portal/                   # Portal web templates
│   │   │   ├── layout.html
│   │   │   ├── login.html
│   │   │   ├── dashboard.html
│   │   │   └── document-detail.html
│   │   ├── ride/                     # RIDE templates
│   │   └── email/                    # Email templates
│   └── xsd/                          # Esquemas XSD del SRI (versionados)
│       ├── factura_v2.1.0.xsd
│       ├── notaCredito_v1.1.0.xsd
│       ├── notaDebito_v1.0.0.xsd
│       ├── comprobanteRetencion_v2.0.0.xsd
│       ├── guiaRemision_v1.1.0.xsd
│       └── liquidacionCompra_v1.1.0.xsd
│
├── src/test/
│   ├── java/auracore/key49/         # Tests organizados por paquete
│   └── resources/
│       └── test-cert.p12            # Certificado de pruebas
│
├── db/migrations/                    # Scripts SQL de referencia (ejecución manual por DBA)
└── pom.xml                           # POM único (single-module Maven)
```

## Patrones de Diseño Aplicados

### 1. Pipeline Pattern (procesamiento de documentos)

Cada comprobante pasa por un pipeline de stages:

```
CREATED → SIGNED → SENT → RECEIVED → AUTHORIZED → NOTIFIED
                              ↓
                          REJECTED (DEVUELTA)
                              ↓
                          RETRY (backoff) → SENT
                              ↓
                          FAILED (DLQ)

Post-autorización:
  AUTHORIZED → VOIDED (anulado localmente)
  NOTIFIED → VOIDED (anulado localmente)
```

### 2. Outbox Pattern (garantía de envío)

Los eventos de dominio se persisten en una tabla `outbox` dentro de la misma transacción que el cambio de estado. Un poller lee la outbox y publica a RabbitMQ. Esto garantiza exactly-once semantics.

### 3. Circuit Breaker (resilencia ante SRI)

MicroProfile Fault Tolerance con `@CircuitBreaker` en el cliente SOAP:

- Umbral de fallo: 5 errores consecutivos
- Ventana de espera: 30 segundos
- Fallback: encolar para retry posterior

### 4. Strategy Pattern (builders de XML)

Un builder por tipo de documento que implementa `DocumentXmlBuilder<T>`:

```java
public interface DocumentXmlBuilder<T extends DocumentRequest> {
    String buildXml(T request, TenantConfig tenant);
    String getDocumentType();
    String getXsdVersion();
}
```

### 5. Template Method (generadores de RIDE)

Clase base `AbstractRideGenerator` con el layout común del RIDE. Subclases implementan la sección de detalle específica por tipo de documento.

## Configuración de RabbitMQ

### Exchanges

| Exchange          | Tipo    | Descripción                      |
| ----------------- | ------- | -------------------------------- |
| `key49.documents` | topic   | Exchange principal de documentos |
| `key49.retry`     | headers | Exchange de reintentos con delay |
| `key49.dlq`       | fanout  | Dead letter exchange             |

### Queues

| Queue              | Routing Key     | Descripción                               |
| ------------------ | --------------- | ----------------------------------------- |
| `key49.sign`       | `doc.sign`      | Documentos pendientes de firma            |
| `key49.send`       | `doc.send`      | Documentos firmados listos para SRI       |
| `key49.authorize`  | `doc.authorize` | Polling de autorización                   |
| `key49.notify`     | `doc.notify`    | Generación RIDE + email + webhook         |
| `key49.retry.5s`   | —               | Retry delay 5 segundos                    |
| `key49.retry.15s`  | —               | Retry delay 15 segundos                   |
| `key49.retry.45s`  | —               | Retry delay 45 segundos                   |
| `key49.retry.135s` | —               | Retry delay 135 segundos                  |
| `key49.retry.405s` | —               | Retry delay 405 segundos (último intento) |
| `key49.dlq`        | —               | Dead letter queue (errores definitivos)   |

### Política de Reintentos

```
Intento 1: inmediato
Intento 2: 5 segundos (cola key49.retry.5s con TTL)
Intento 3: 15 segundos
Intento 4: 45 segundos
Intento 5: 135 segundos
Intento 6: 405 segundos
Fallo definitivo → DLQ + webhook de error al tenant
```

Los reintentos aplican SOLO para errores de infraestructura (timeout, conexión rechazada, HTTP 500 del SRI). Los errores de negocio (XML inválido, firma incorrecta, RUC suspendido) van directo a estado FAILED sin reintento.

## Máquina de Estados de Documentos

Cada transición de estado está validada en código. No se permite transición arbitraria.

### Transiciones válidas

| Estado Actual | Estado Destino | Trigger                                                                  | Responsable         |
| ------------- | -------------- | ------------------------------------------------------------------------ | ------------------- |
| `CREATED`     | `SIGNED`       | XML generado, clave de acceso calculada, firma XAdES-BES aplicada        | `SignConsumer`      |
| `CREATED`     | `FAILED`       | Error irrecuperable en generación XML o firma (ej: certificado inválido) | `SignConsumer`      |
| `SIGNED`      | `SENT`         | XML enviado al SRI vía SOAP Recepción                                    | `SendConsumer`      |
| `SIGNED`      | `RETRY`        | Error de infraestructura al enviar (timeout, conexión rechazada)         | `SendConsumer`      |
| `SIGNED`      | `REJECTED`     | SRI devuelve DEVUELTA con error de negocio (cód 35, 45, 52, 65)          | `SendConsumer`      |
| `SENT`        | `RECEIVED`     | SRI confirma RECIBIDA (pendiente autorización)                           | `SendConsumer`      |
| `RECEIVED`    | `AUTHORIZED`   | SRI devuelve AUTORIZADO                                                  | `AuthorizeConsumer` |
| `RECEIVED`    | `REJECTED`     | SRI devuelve NO AUTORIZADO con error de negocio                          | `AuthorizeConsumer` |
| `RECEIVED`    | `RETRY`        | Error de infraestructura al consultar autorización                       | `AuthorizeConsumer` |
| `AUTHORIZED`  | `NOTIFIED`     | RIDE generado + email enviado + webhook disparado                        | `NotifyConsumer`    |
| `AUTHORIZED`  | `VOIDED`       | Anulación local solicitada por el tenant                                 | API REST            |
| `NOTIFIED`    | `VOIDED`       | Anulación local solicitada por el tenant                                 | API REST            |
| `RETRY`       | `SIGNED`       | Re-procesamiento tras espera (vuelve a firmar si es necesario)           | Retry mechanism     |
| `RETRY`       | `SENT`         | Re-envío al SRI tras espera                                              | Retry mechanism     |
| `RETRY`       | `FAILED`       | Reintentos agotados (max 6)                                              | Retry mechanism     |
| `REJECTED`    | `CREATED`      | Reciclaje: cliente reenvía documento con datos corregidos                | API REST            |
| `FAILED`      | `CREATED`      | Reciclaje: cliente reenvía documento con datos corregidos                | API REST            |

### Reciclaje de documentos fallidos

Cuando un cliente reenvía un documento con los mismos datos de unicidad (tipo + establecimiento + punto de emisión + secuencial) y el documento existente está en estado `REJECTED` o `FAILED`, el sistema **recicla** el documento existente en lugar de rechazarlo. Esto permite al cliente corregir errores y reenviar sin necesidad de cambiar el secuencial.

El reciclaje:

1. Resetea el estado a `CREATED`
2. Limpia campos de procesamiento (`access_key`, `authorization_number`, errores, rutas XML)
3. Actualiza el `request_payload` con los nuevos datos
4. Crea un nuevo evento outbox para iniciar el procesamiento

Si el documento existente está en un estado **activo** (`CREATED`, `SIGNED`, `SENT`, `RECEIVED`, `RETRY`) o **completado** (`AUTHORIZED`, `NOTIFIED`, `VOIDED`), el sistema devuelve HTTP 409 con información del documento existente (id, estado, clave de acceso si disponible).

### Transiciones prohibidas (ejemplos)

- `AUTHORIZED` → `CREATED` (no se puede volver atrás)
- `VOIDED` → cualquier estado (estado terminal)

### Estados terminales

- **Terminales absolutos**: `VOIDED` (no permiten ninguna transición)
- **Terminales reciclables**: `REJECTED`, `FAILED` (solo permiten transición a `CREATED` vía reciclaje de documento)

### Implementación

Usar un enum `DocumentStatus` con método `canTransitionTo(DocumentStatus target)` que valida las transiciones permitidas:

```java
public enum DocumentStatus {
    CREATED, SIGNED, SENT, RECEIVED, AUTHORIZED, NOTIFIED,
    REJECTED, FAILED, RETRY, VOIDED;

    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = Map.of(
        CREATED,    Set.of(SIGNED, FAILED),
        SIGNED,     Set.of(SENT, RETRY, REJECTED),
        SENT,       Set.of(RECEIVED),
        RECEIVED,   Set.of(AUTHORIZED, REJECTED, RETRY),
        AUTHORIZED, Set.of(NOTIFIED, VOIDED),
        NOTIFIED,   Set.of(VOIDED),
        RETRY,      Set.of(SIGNED, SENT, FAILED),
        REJECTED,   Set.of(CREATED),
        FAILED,     Set.of(CREATED),
        VOIDED,     Set.of()
    );

    public boolean canTransitionTo(DocumentStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

## Outbox Poller

El Outbox Pattern requiere un poller que lea eventos pendientes de la tabla `outbox` y los publique a RabbitMQ.

### Diseño

- **Mecanismo**: `@Scheduled` de Quarkus (cron en `application.properties`)
- **Intervalo**: cada 500ms (`key49.outbox.poll-interval=500ms`)
- **Batch size**: 50 eventos por ciclo (`key49.outbox.batch-size=50`)
- **Orden**: `ORDER BY created_at ASC` (FIFO)
- **Idempotencia**: el consumer debe ser idempotente (el poller puede re-publicar si falla el `UPDATE published = true`)

### Flujo del poller

```
1. SELECT * FROM outbox WHERE published = false ORDER BY created_at ASC LIMIT 50
2. Para cada evento:
   a. Publicar mensaje a RabbitMQ (exchange key49.documents, routing key según event_type)
   b. UPDATE outbox SET published = true, published_at = now() WHERE outbox_id = ?
3. Si falla la publicación: log error, el evento se re-procesará en el siguiente ciclo
4. Si falla el UPDATE: el mensaje se publica 2 veces, pero el consumer es idempotente
```

### Limpieza

Un job nocturno (`@Scheduled(cron = "0 0 2 * * ?")`) elimina eventos publicados con más de 7 días:

```sql
DELETE FROM outbox WHERE published = true AND published_at < now() - interval '7 days';
```

## Uso de Redis

| Funcionalidad    | Clave                                 | TTL   | Descripción                                                        |
| ---------------- | ------------------------------------- | ----- | ------------------------------------------------------------------ |
| Rate limiting    | `ratelimit:{api_key_prefix}:{window}` | 60s   | Sliding window counter por API key                                 |
| Cache de tenant  | `tenant:{tenant_id}`                  | 300s  | Config del tenant (evita query a `public.tenants` en cada request) |
| Idempotencia     | `idempotency:{tenant_id}:{key}`       | 24h   | Respuesta cacheada para X-Idempotency-Key                          |
| Sesión portal    | `session:{session_id}`                | 30min | Datos de sesión del portal web                                     |
| Lock distribuido | `lock:document:{document_id}`         | 30s   | Evita procesamiento concurrente del mismo documento                |

**Nota**: Redis es cache/session store. Si Redis cae, el sistema sigue funcionando (rate limiting permisivo, queries directas a BD, sesiones expiran). No hay datos críticos solo en Redis.

## Trazabilidad y Correlación

### Estrategia: OpenTelemetry (estándar de la industria)

- **Quarkus OpenTelemetry** (`quarkus-opentelemetry`): instrumentación automática de HTTP, JDBC, RabbitMQ.
- **Trace ID**: propagado automáticamente a través de HTTP headers y RabbitMQ message properties.
- **Request ID**: generado por Key49 (`req_{nanoid}`) y retornado en `meta.request_id`. Se propaga como atributo de span.

### Propagación en colas RabbitMQ

SmallRye Reactive Messaging con OpenTelemetry propaga el trace context automáticamente en los headers del mensaje AMQP. Cada consumer hereda el span del producer.

### Headers de respuesta

```
X-Request-Id: req_abc123        # ID único de Key49
X-Trace-Id: 4bf92f3577b347...   # OpenTelemetry trace ID
```

### Colector

- **Desarrollo**: logs a consola con trace/span IDs
- **Producción**: exportar a Grafana Tempo o Jaeger via OTLP

## Gestión de Conexiones PostgreSQL por Tenant

### Comportamiento con Reactive PgPool

Quarkus usa Vert.x Reactive PgPool con un **pool único de conexiones**. El `SET search_path` se ejecuta al inicio de cada operación reactiva y aplica solo a esa conexión durante la transacción.

### Garantía de aislamiento

```java
// En TenantConnectionFilter (ejecutado por el filtro de autenticación)
public Uni<Void> setTenantSchema(String schemaName) {
    return pool.query("SET search_path TO '" + schemaName + "', public")
        .execute()
        .replaceWithVoid();
}
```

**IMPORTANTE**: Usar queries parametrizadas o validar `schemaName` contra `[a-z0-9_]+` para prevenir SQL injection. El `schemaName` proviene de `public.tenants.schema_name` (fuente confiable), pero se valida por defensa en profundidad.

### Consideraciones

- El pool es global (no se crea un pool por tenant), lo cual es eficiente.
- `SET search_path` es una operación ligera (~0.1ms).
- Con Vert.x, cada request obtiene una conexión del pool, ejecuta operaciones, y la devuelve. No hay riesgo de que dos requests compartan la misma conexión simultáneamente.
- Pool size recomendado: `max(numero_cores * 2, 10)` para inicio. Ajustar según carga.

## Sesión del Portal Web

### Estrategia: Redis-backed sessions

- **Login**: el tenant se autentica con su API key en `/portal/login`.
- **Sesión**: se crea un session ID (UUID) almacenado en Redis con TTL de 30 minutos.
- **Cookie**: `KEY49_SESSION={session_id}`, `HttpOnly`, `Secure`, `SameSite=Strict`.
- **Datos en sesión**: `tenant_id`, `schema_name`, `legal_name` (solo lectura, no datos sensibles).
- **Renovación**: cada request renueva el TTL de la sesión.
- **CSRF**: no necesario porque el portal es solo lectura (no hay formularios POST que modifiquen datos). HTMX usa GET para todas las actualizaciones parciales.
- **Logout**: `DELETE` de la clave en Redis + borrar cookie.

### Fallback si Redis no disponible

Si Redis cae, las sesiones existentes se pierden (el usuario debe re-autenticarse). No hay datos críticos en la sesión.

## Health Checks

El sistema expone los siguientes health checks en `/q/health`:

- **SRI Recepción**: ping al WSDL de pruebas/producción
- **SRI Autorización**: ping al WSDL de autorización
- **PostgreSQL**: conexión activa
- **RabbitMQ**: conexión y canales abiertos
- **MinIO**: bucket accesible
- **Redis**: conexión y ping
- **Certificados**: expiración de certificados de tenants (warning < 30 días)

## Variables de Entorno

```properties
# Base de datos
KEY49_DB_URL=jdbc:postgresql://localhost:5432/key49
KEY49_DB_USER=key49
KEY49_DB_PASSWORD=secret

# RabbitMQ
KEY49_RABBITMQ_HOST=localhost
KEY49_RABBITMQ_PORT=5672
KEY49_RABBITMQ_USER=key49
KEY49_RABBITMQ_PASSWORD=secret

# MinIO
KEY49_MINIO_ENDPOINT=http://localhost:9000
KEY49_MINIO_ACCESS_KEY=key49
KEY49_MINIO_SECRET_KEY=secret
KEY49_MINIO_BUCKET=key49-documents

# Redis
KEY49_REDIS_URL=redis://localhost:6379

# SRI
KEY49_SRI_ENVIRONMENT=test  # test | production
KEY49_SRI_RECEPTION_TIMEOUT_MS=3000
KEY49_SRI_AUTHORIZATION_TIMEOUT_MS=5000
KEY49_SRI_MAX_RETRIES=6

# Zona horaria
KEY49_TIMEZONE=America/Guayaquil

# Seguridad
KEY49_MASTER_KEY=base64-encoded-256-bit-key  # Clave maestra para cifrar .p12
KEY49_JWT_SECRET=secret
KEY49_JWT_ISSUER=key49
KEY49_JWT_EXPIRATION_MINUTES=60

# Email
KEY49_SMTP_HOST=smtp.example.com
KEY49_SMTP_PORT=587
KEY49_SMTP_USER=noreply@key49.ec
KEY49_SMTP_PASSWORD=secret
KEY49_SMTP_FROM=noreply@key49.ec

# Outbox poller
KEY49_OUTBOX_POLL_INTERVAL=500ms
KEY49_OUTBOX_BATCH_SIZE=50

# OpenTelemetry
QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
QUARKUS_OTEL_SERVICE_NAME=key49
```

# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/),
y este proyecto adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [0.16.5] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en factura XSD v2.1.0 (T-044): clase `InvoiceXsdMandatoryFieldsTest` con 26 tests parametrizados que verifican que el XSD rechaza el XML cuando falta un campo obligatorio en `infoTributaria`, `infoFactura` o `detalles`

### Corregido

- Método duplicado `paymentsIncluded()` en `WithholdingXmlBuilderTest.Pagos` renombrado a `paymentsIncludedInMinimal()`

## [0.16.4] - 2026-04-08

### Agregado

- Tests de validación XSD para `WaybillXmlBuilder` (T-043): guía de remisión simple, múltiples destinatarios y mínima contra `GuiaRemision_V1.1.0.xsd`

## [0.16.3] - 2026-04-08

### Agregado

- Tests de validación XSD para `WithholdingXmlBuilder` (T-042): retención simple, múltiples docs sustento y mínima contra `ComprobanteRetencion_V2.0.0.xsd`

### Corregido

- `WithholdingDataFixtures`: formato de `numDocSustento` corregido a 15 dígitos sin guiones (patrón XSD `[0-9]{15}`)
- `WithholdingDataFixtures`: `minimalSupportingDocument()` ahora incluye pago obligatorio (requerido por XSD)

## [0.16.2] - 2026-04-08

### Agregado

- Tests de validación XSD para `DebitNoteXmlBuilder` (T-041): nota de débito simple, múltiples motivos y mínima contra `NotaDebito_V1.0.0.xsd`

## [0.16.1] - 2026-04-08

### Agregado

- Tests de validación XSD para `CreditNoteXmlBuilder` (T-040): nota de crédito simple, multi-ítem y mínima contra `NotaCredito_V1.1.0.xsd`

## [0.16.0] - 2026-04-08

### Cambiado

- **Refactorización mayor**: consolidación de 10 módulos Maven (key49-api, key49-core, key49-xml, key49-signer, key49-sri, key49-queue, key49-ride, key49-notify, key49-storage, key49-admin) en un solo módulo raíz
  - Todo el código fuente migrado a `src/main/java/` y `src/test/java/` bajo el paquete `auracore.key49.*`
  - Eliminados los 10 subdirectorios de módulos y sus POMs individuales
  - POM raíz convertido de parent multi-módulo a proyecto único con todas las dependencias
- **Migración de Hibernate Reactive a Hibernate ORM clásico (imperativo)**
  - Reemplazado `quarkus-hibernate-reactive-panache` + `quarkus-reactive-pg-client` por `quarkus-hibernate-orm-panache` + `quarkus-jdbc-postgresql`
  - Eliminadas todas las cadenas `Uni<T>` / `Uni.createFrom()` / `.chain()` / `.onItem()` — ahora código imperativo directo
  - `TenantConnectionManager`: `withTenantSession()` y `withTenantTransaction()` ahora usan `EntityManager` + `@Transactional` con `SET search_path` vía native query
  - Consumers RabbitMQ mantienen `@Blocking` (virtual threads)
  - Repositorios Panache ahora extienden `PanacheRepositoryBase` imperativo
- Campos JSONB en entidades: migrados de `@Type(JsonBinaryType.class)` a `@JdbcTypeCode(SqlTypes.JSON)`

### Agregado

- `quarkus-junit5-mockito` como dependencia de test para soporte de `@InjectMock`
- 7 nuevos tests de integración para el pipeline async (35 test methods):
  - `SignConsumerIntegrationTest` — firma XAdES-BES, clave acceso, outbox
  - `SendConsumerIntegrationTest` — SOAP recepción: éxito, negocio, infra, reintentos
  - `AuthorizeConsumerIntegrationTest` — SOAP autorización: éxito, negocio, infra, reintentos
  - `NotifyConsumerIntegrationTest` — webhook dispatch, transición NOTIFIED
  - `DlqConsumerIntegrationTest` — DLQ: RETRY→FAILED, estados terminales
  - `ConsumerErrorHandlerIntegrationTest` — persistencia de errores, transición FAILED
  - `OutboxPollerIntegrationTest` — polling, publicación, marking published
- `TestSchemaHelper` — helper compartido con DDL para tests de integración de consumers
- Tareas T-040 a T-052 en TASKS.md — Fase 5: cobertura de tests XML/XSD

### Corregido

- Todas las entidades, repositorios, servicios, recursos, filtros y consumers convertidos de reactive a imperativo
- 1376 tests pasan (0 fallos, 0 errores) tras la migración completa

## [0.15.1] - 2026-04-08

### Corregido

- Jandex plugin faltante en key49-queue y key49-sri — CDI no descubría beans de estos módulos
- `@WithSession` faltante en `OutboxPoller`, `OutboxCleanup`, `RetryPoller` — Panache requiere sesión fuera de JAX-RS
- `quarkus.index-dependency` para key49-queue y key49-notify — SmallRye Messaging no detectaba `@Incoming`/`@Outgoing`
- `Document.lastErrorMessage` — `columnDefinition = "text"` para coincidir con schema SQL (era varchar 255)
- `DocumentRepository.findRetryReady()` — reemplazar `now()` HQL por parámetro `Instant.now()` (incompatibilidad de tipos)
- Consumers RabbitMQ: aceptar `JsonObject` en lugar de `DocumentEvent` — SmallRye RabbitMQ no auto-deserializa POJOs
- `DocumentEvent.fromJson()` — usar snake_case (`document_id`, `tenant_schema_name`) por config global `SNAKE_CASE` de Jackson
- `@WithSession` en los 5 consumers de RabbitMQ (Sign, Send, Authorize, Notify, DLQ)
- `InvoiceXmlBuilder.appendDecimalElement()` — null-safe para `BigDecimal` opcionales (discount)

### Agregado

- `ConsumerErrorHandler` — servicio que persiste errores inesperados de consumers en BD (`last_error_message`, transición a FAILED)
- Los 5 consumers ahora registran errores en BD en lugar de solo loguearlos

## [0.15.0] - 2026-04-07

### Agregado

- Alertas operativas con 5 reglas y notificación email/webhook (T-037)
  - `AlertResult`, `AlertState`, `AlertRule` — modelo de alertas inmutable
  - `AlertStateRepository` — persistencia de estado de alertas en Redis (HSET/HGETALL, TTL 7 días)
  - `AlertNotifier` — notificaciones por email (ReactiveMailer) y webhook (HMAC-SHA256)
  - `SriHealthAlertRule` — alerta cuando SRI no responde > 5 minutos (reutiliza health checks)
  - `DlqAlertRule` — alerta cuando DLQ tiene mensajes (RabbitMQ Management API)
  - `CertificateExpiryAlertRule` — alerta cuando certificados vencen en < 30 días
  - `ErrorRateAlertRule` — alerta cuando tasa de error > 5% (Micrometer counters)
  - `QueueDepthAlertRule` — alerta cuando colas de procesamiento > 1000 mensajes
  - `AlertEvaluator` — orquestador con evaluación cada 60s (infra) y 6h (certificados)
  - Transiciones de estado OK→FIRING→RESOLVED con reminders cada 4 horas
  - 23 tests unitarios para modelo, reglas y evaluador
  - 14 propiedades de configuración con valores por defecto sensatos

## [0.14.0] - 2026-04-07

### Agregado

- Guía para desarrolladores (T-036)
  - `docs/DEVELOPER-GUIDE.md` — documentación completa para integración de clientes
  - Quickstart en 4 pasos con verificación de conexión y primera factura
  - Referencia de campos para los 6 tipos de documento: Factura, Nota de Crédito, Nota de Débito, Guía de Remisión, Comprobante de Retención, Liquidación de Compra
  - Ejemplos curl para cada tipo de documento
  - Ejemplos de integración en Python, Node.js y Java
  - Catálogo de errores con códigos, descripciones y acciones
  - Secciones de idempotencia, rate limiting, webhooks, anulación, descarga XML/RIDE

## [0.13.0] - 2026-04-07

### Agregado

- Tests end-to-end para todos los tipos de documento (T-035)
  - `CreditNoteEndToEndTest` — 25 tests E2E para notas de crédito
  - `DebitNoteEndToEndTest` — 25 tests E2E para notas de débito
  - `WaybillEndToEndTest` — 24 tests E2E para guías de remisión
  - `WithholdingEndToEndTest` — 24 tests E2E para comprobantes de retención
  - `PurchaseClearanceEndToEndTest` — 25 tests E2E para liquidaciones de compra
  - Cada test cubre: creación, idempotencia, consulta por ID, listado con filtros, duplicados, validaciones, XML/RIDE no disponible, anulación, paginación, resend-email y outbox

### Corregido

- `WaybillService` y `WithholdingService` no asignaban `createdAt`/`updatedAt` al persistir documentos
- `WaybillService` y `WithholdingService` creaban `OutboxEvent` antes de `session.persist(doc)`, causando `aggregate_id` nulo
- Rate limit en tests: aumentado `rate_limit_rpm` a 10000 en todos los tests E2E para evitar 429 por bucket compartido

## [0.12.0] - 2026-04-07

### Agregado

- RIDE templates para todos los tipos de documento (T-034)
  - `PurchaseClearanceRideData`, `PurchaseClearanceRideGenerator` — generación RIDE (PDF) para liquidación de compra con datos de proveedor, ítems, impuestos, pagos, totales, QR y marca de agua
  - 8 tests unitarios para PurchaseClearanceRideGenerator
  - Los 6 tipos de documento ahora tienen RIDE completo: Factura, Nota de Crédito, Nota de Débito, Guía de Remisión, Comprobante de Retención, Liquidación de Compra

## [0.11.0] - 2026-04-07

### Agregado

- Liquidación de Compra — tipo 03, XSD v1.1.0 (T-033)
  - `PurchaseClearanceData`, `PurchaseClearanceXmlBuilder` — generación XML con datos de proveedor, ítems, impuestos, pagos
  - `CreatePurchaseClearanceRequest`, `PurchaseClearanceResponse` — DTOs de API REST
  - `PurchaseClearanceService` — validación de proveedor (tipos ID 04-08), ítems con impuestos, pagos, emisión mismo día
  - `PurchaseClearanceResource` — endpoints REST: POST/GET `/v1/purchase-clearances`, XML, RIDE, void, resend-email
  - `PurchaseClearanceDataMapper` — mapeo Document→PurchaseClearanceData para firma
  - `SignConsumer` actualizado con caso PURCHASE_CLEARANCE
  - 62 tests unitarios (XmlBuilder, Service, DataMapper)

## [0.10.0] - 2026-04-06

### Agregado

- Guía de Remisión — tipo 06, XSD v1.1.0 (T-032)
  - `WaybillData`, `WaybillXmlBuilder` — generación XML con destinatarios, ítems, datos de transportista
  - `CreateWaybillRequest`, `WaybillResponse` — DTOs de API REST
  - `WaybillService` — validación de transportista (tipos ID 04-08), fechas de transporte, placa, destinatarios con ítems
  - `WaybillResource` — endpoints REST: POST/GET `/v1/waybills`, XML, RIDE, void, resend-email
  - `WaybillDataMapper` — mapeo Document→WaybillData para firma
  - `WaybillRideData`, `WaybillRideGenerator` — generación RIDE PDF con secciones por destinatario
  - `SignConsumer` actualizado con caso WAYBILL
  - 52 tests unitarios (XmlBuilder, DataMapper, RideGenerator)

## [0.9.0] - 2026-04-06

### Agregado

- Comprobante de Retención ATS — tipo 07, XSD v2.0.0 (T-031)
  - `WithholdingData`, `WithholdingXmlBuilder` — generación XML con soporte completo de `docsSustento` (impuestos, retenciones, pagos)
  - `CreateWithholdingRequest`, `WithholdingResponse` — DTOs de API REST
  - `WithholdingService` — validación de sujeto retenido, periodo fiscal (MM/yyyy), documentos de sustento, códigos de retención (1=Renta, 2=IVA, 6=ISD)
  - `WithholdingResource` — endpoints REST: POST/GET `/v1/withholdings`, XML, RIDE, void, resend-email
  - `WithholdingDataMapper` — mapeo Document→WithholdingData para firma
  - `WithholdingRideData`, `WithholdingRideGenerator` — generación RIDE PDF con tabla de retenciones
  - `SignConsumer` actualizado con caso WITHHOLDING
  - 46 tests unitarios (XmlBuilder, DataMapper, RideGenerator)

## [0.8.0] - 2026-04-06

### Agregado

- Nota de Débito — tipo 05, XSD v1.0.0 (T-030)
  - `DebitNoteData`: record con estructura de datos para generación XML de nota de débito
  - `DebitNoteXmlBuilder`: genera `<notaDebito>` conforme a XSD SRI v1.0.0
  - `CreateDebitNoteRequest` / `DebitNoteResponse`: DTOs de API REST
  - `DebitNoteService`: lógica de negocio (crear, anular, listar, reenviar email)
  - `DebitNoteResource`: endpoints REST en `/v1/debit-notes` (CRUD + xml + ride + void + resend)
  - `DebitNoteDataMapper`: mapeo Document + Tenant → DebitNoteData para firma en cola
  - `SignConsumer`: soporte de nota de débito en despachador XML por tipo de documento
  - `DebitNoteRideData` / `DebitNoteRideGenerator`: generación RIDE (PDF) de notas de débito
  - Sección `<motivos>` con razón y valor (sin tabla de detalles por ítem)
  - Impuestos a nivel de documento con campo `tarifa` requerido
  - Pagos opcionales en `<infoNotaDebito>`
  - Tests: DebitNoteXmlBuilderTest, DebitNoteServiceTest, DebitNoteRideGeneratorTest, DebitNoteDataMapperTest
- Nota de Crédito — tipo 04, XSD v1.1.0 (T-029)
  - `CreditNoteData`: record con estructura de datos para generación XML de nota de crédito
  - `CreditNoteXmlBuilder`: genera `<notaCredito>` conforme a XSD SRI v1.1.0
  - `CreateCreditNoteRequest` / `CreditNoteResponse`: DTOs de API REST
  - `CreditNoteService`: lógica de negocio (crear, anular, listar, reenviar email)
  - `CreditNoteResource`: endpoints REST en `/v1/credit-notes` (CRUD + xml + ride + void + resend)
  - `CreditNoteDataMapper`: mapeo Document + Tenant → CreditNoteData para firma en cola
  - `SignConsumer`: despachador XML por tipo de documento (factura / nota de crédito)
  - `CreditNoteRideData` / `CreditNoteRideGenerator`: generación RIDE (PDF) de notas de crédito
  - Campos modificados: modifiedDocumentCode, modifiedDocumentNumber, modifiedDocumentDate, reason
  - Tests: CreditNoteXmlBuilderTest, CreditNoteServiceTest, CreditNoteRideGeneratorTest, CreditNoteDataMapperTest
- Configuración de producción (T-028)
  - `SecurityHeadersFilter`: headers de seguridad OWASP en todas las respuestas JAX-RS
    - X-Frame-Options: DENY, X-Content-Type-Options: nosniff, Referrer-Policy, Permissions-Policy, CSP
  - CORS por perfil: dev/test permisivo (`/.*/`), producción restringido (orígenes, métodos, headers)
  - HSTS para producción: `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - Graceful shutdown: `quarkus.shutdown.timeout=30s`
  - Dockerfile multi-stage (JVM): build con Maven + runtime con Eclipse Temurin 25 JRE Alpine
  - Usuario no-root (`key49`) en contenedor
  - Eliminación de `quarkus.http.cors=true` deprecado (Quarkus 3.x auto-detecta por `cors.origins`)
  - 6 tests nuevos (896 total proyecto, 0 failures)

## [0.7.0] - 2026-04-06

### Agregado

- Portal web de consulta para tenants (T-028a)
  - Server-side rendering con Qute + Pico CSS v2 + HTMX v2.0.4
  - Autenticación por API key con sesiones Redis (cookie HttpOnly, TTL 30min)
  - PortalAuthFilter (priority 15) valida sesión en todas las rutas /portal/\*
  - Pantalla login: formulario con API key, mensajes de error
  - Pantalla dashboard: tabla de documentos con filtros (estado, fechas, búsqueda) + paginación
  - Pantalla detalle: datos del documento, receptor, totales, timeline de procesamiento
  - Polling automático de estado con HTMX (hx-trigger="every 5s") para documentos en proceso
  - Endpoint parcial GET /portal/documents/{id}/status para actualizaciones HTMX
  - Portal solo lectura — no permite crear ni modificar documentos
  - 10 tests E2E
- Gestión de tenants para administración (T-021)
  - `CertificateMetadataExtractor`: extracción de metadata (subject, serial, expiración, issuer) de certificados .p12
  - `TenantAdminService`: CRUD de tenants en esquema público con validación de RUC y unicidad de schema_name
  - REST API `/v1/admin/tenants`: crear, listar (paginación + filtro por status), consultar, actualizar
  - Upload de certificado .p12 (`POST /v1/admin/tenants/:id/certificate`): validación, extracción de metadata, cifrado AES-256-GCM
  - Consulta de estado de certificado (`GET /v1/admin/tenants/:id/certificate/status`): subject, serial, expiración, días restantes
  - DTOs: `CreateTenantRequest`, `UpdateTenantRequest`, `TenantResponse` (con `CertificateSummary`), `CertificateStatusResponse`
  - `TenantExceptionMapper` para errores de negocio de tenants
  - 50 tests nuevos (768 total proyecto, 0 failures)
- Endpoints de perfil de tenant autenticado (T-022)
  - `TenantProfileResource`: endpoints en `/v1/tenant` para self-service del tenant
  - `GET /v1/tenant/profile`: obtener datos del tenant autenticado vía API key
  - `PUT /v1/tenant/profile`: actualizar datos propios (razón social, dirección, webhook, email)
  - `POST /v1/tenant/certificate`: subir certificado .p12 con validación y cifrado
  - `GET /v1/tenant/certificate/status`: verificar vigencia del certificado
  - `UpdateProfileRequest`: DTO restringido sin campos administrativos (status, rateLimitRpm)
  - Integración con `TenantContext` para resolver tenant desde API key
  - 16 tests nuevos (784 total proyecto, 0 failures)
- Gestión de API keys para tenants (T-023)
  - `ApiKeyManagementService`: CRUD de API keys (creación, listado, consulta, revocación)
  - Validación de datos: nombre requerido (max 100 chars), environment (test/production)
  - `POST /v1/tenant/api-keys`: crear nueva API key (devuelve rawKey solo una vez)
  - `GET /v1/tenant/api-keys`: listar API keys del tenant autenticado
  - `GET /v1/tenant/api-keys/:id`: consultar API key por ID con verificación de ownership
  - `DELETE /v1/tenant/api-keys/:id`: revocar API key (409 si ya revocada)
  - DTOs: `CreateApiKeyRequest`, `ApiKeyResponse` (fromEntity/fromCreated con rawKey condicional)
  - `ApiKeyExceptionMapper`: mapeo de errores a respuestas JSON con code/message
  - `@JsonInclude(NON_NULL)` para omitir rawKey en respuestas de listado/consulta
  - 34 tests nuevos (818 total proyecto, 0 failures)
- Dashboard de métricas para tenant (T-025)
  - `MetricsService`: consulta de documentos agrupados por estado con HQL en esquema tenant
  - `GET /v1/metrics/summary`: resumen de hoy, mes actual, certificado y última factura
  - `MetricsSummaryResponse`: DTO con `PeriodSnapshot` (total, authorized, rejected, pending, failed)
  - Clasificación de estados: AUTHORIZED+NOTIFIED+VOIDED → authorized; REJECTED → rejected; FAILED → failed; resto → pending
  - Días restantes de certificado calculados desde `certificate_expiration` del tenant
  - `lastInvoiceAt`: fecha de última autorización (`MAX(authorization_date)`)
  - Queries secuenciales para compatibilidad con Hibernate Reactive (una sesión, sin paralelismo)
  - 20 tests nuevos (838 total proyecto, 0 failures)
- Rate limiting con Redis (T-026)
  - `RateLimiter`: sliding window con Redis sorted sets y Lua script atómico (ZREMRANGEBYSCORE + ZADD + ZCARD + PEXPIRE)
  - `RateLimitFilter`: `@ServerRequestFilter(priority=20)` que aplica rate limit después de autenticación
  - `@ServerResponseFilter` para inyectar headers `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
  - Respuesta 429 con `Retry-After` y error code `RATE_LIMIT_EXCEEDED` cuando se excede el límite
  - Configuración por tenant vía campo `rate_limit_rpm` en tabla `tenants` (default 100 RPM)
  - Clave Redis: `ratelimit:{api_key_prefix}`, ventana deslizante de 60 segundos
  - Modo permisivo si Redis no disponible (permite request, log warning)
  - `TenantContext` extendido con `rateLimitRpm` y `apiKeyPrefix`
  - `ApiKeyAuthFilter` actualizado: priority=10, consulta `rate_limit_rpm`, pobla TenantContext
  - 16 tests nuevos (168 total proyecto, 0 failures)
- Endpoint XML Raw para documentos pre-armados (T-028b)
  - `POST /v1/documents/raw`: recibe XML completo con `Content-Type: application/xml`
  - Header `X-Document-Type` obligatorio para indicar tipo de comprobante (01-07)
  - Validación XSD dinámica según tipo de documento
  - Validación `<codDoc>` en XML coincide con header `X-Document-Type`
  - Extracción de metadatos: receptor (varía por tipo), totales, serie, email de infoAdicional
  - Clave de acceso siempre generada por Key49, reemplaza cualquier `<claveAcceso>` del XML
  - RUC extraído del XML (`<ruc>` en `<infoTributaria>`)
  - Prevención XXE: deshabilitación de DOCTYPE y entidades externas
  - Documento persiste con `request_origin = 'XML_RAW'` y `original_xml` almacena XML final
  - `GET /v1/documents/raw/:id`: consultar estado de documento
  - `RawDocumentResponse`: DTO con campo `origin` para diferenciar de documentos JSON
  - Idempotencia con `X-Idempotency-Key`, unicidad por serie, duplicados (409)
  - Continúa pipeline normal: firmar → enviar → autorizar → notificar
  - Errores: MISSING_DOCUMENT_TYPE, INVALID_XML_STRUCTURE, DOCUMENT_TYPE_MISMATCH, XSD_VALIDATION_FAILED
  - 10 tests E2E nuevos (186 total proyecto, 0 failures)
- Health checks y observabilidad (T-027)
  - `MinioHealthCheck` (@Readiness): verifica accesibilidad del bucket en MinIO
  - `SriReceptionHealthCheck` (@Liveness): HTTP HEAD al WSDL de Recepción del SRI
  - `SriAuthorizationHealthCheck` (@Liveness): HTTP HEAD al WSDL de Autorización del SRI
  - `CertificateExpirationHealthCheck` (@Readiness): detecta certificados que vencen en < 30 días
  - `CertificateExpirationNotifier`: job diario (08:00 ECT) que envía email y webhook `certificate.expiring`
  - `TracingFilter`: genera `X-Request-Id` (req\_{uuid}) y `X-Trace-Id` (OpenTelemetry) en todas las respuestas
  - OpenTelemetry configurado: tracing automático HTTP, JDBC, RabbitMQ
  - Logs con traceId/spanId en formato de consola (desarrollo)
  - Producción: exportar a Grafana Tempo vía OTLP (`KEY49_OTEL_TRACES_EXPORTER=otlp`)
  - Jandex indexing para key49-admin (descubrimiento de beans CDI)
  - 15 tests nuevos (176 total proyecto, 0 failures)

## [0.6.0] - 2026-04-05

### Agregado

- Generador de RIDE (PDF) para factura electrónica (T-015)
  - `InvoiceRideGenerator`: generación programática con OpenPDF 2.0.3
  - Secciones: encabezado emisor, datos receptor, tabla de ítems, impuestos, totales, pagos, info adicional
  - `QrCodeGenerator`: código QR con clave de acceso (ZXing 3.5.3)
  - Logo del emisor opcional (PNG/JPEG)
  - Marca de agua "SIN AUTORIZACIÓN" para documentos no autorizados
  - `RideData`: record con datos de presentación para el RIDE
  - `RideGenerationException`: excepción específica del módulo
  - 31 tests nuevos (562 total proyecto, 0 failures)
- Almacenamiento de artefactos en MinIO/S3 (T-016)
  - `ObjectStorageService`: almacenamiento y descarga con MinIO Java SDK 8.5.17
  - `StoragePath`: construcción de rutas `{tenant}/{year}/{month}/{docType}/{accessKey}/{filename}`
  - `DocumentArtifact`: enum con 4 tipos (unsigned.xml, signed.xml, authorized.xml, ride.pdf)
  - `StorageException`: excepción específica del módulo
  - Configuración: endpoint, access-key, secret-key, bucket, region con env vars
  - 31 tests nuevos (593 total proyecto, 0 failures)
- Servicio de email para entrega de comprobantes (T-017)
  - `EmailService`: envío reactivo con Quarkus Mailer + Qute template
  - `EmailData`: record con datos de emisor, receptor, adjuntos y parseEmails()
  - Template HTML responsive para email de entrega (document-delivery.html)
  - Soporte múltiples destinatarios separados por `;` (primer email TO, resto CC)
  - Adjuntos opcionales: RIDE (PDF) y XML autorizado
  - Configuración SMTP con env vars, flag `key49.email.enabled` para desactivar
  - 23 tests nuevos (616 total proyecto, 0 failures)
- Endpoints REST de Factura (T-018)
  - POST /v1/invoices — crear factura con validación completa y cola de procesamiento
  - GET /v1/invoices/:id — consultar factura con detalle completo
  - GET /v1/invoices — listar con filtros (status, fechas, recipient_id, access_key, document_type), paginación y ordenamiento
  - GET /v1/invoices/:id/xml — descargar XML desde MinIO
  - GET /v1/invoices/:id/ride — descargar RIDE (PDF) desde MinIO
  - POST /v1/invoices/:id/resend-email — reenviar email vía outbox
  - POST /v1/invoices/:id/void — anulación local (valida estado, plazo día 7, consumidor final)
  - DTOs: CreateInvoiceRequest, InvoiceResponse (summary/detail), ApiResponse, PagedResponse, VoidRequest
  - BusinessException con detalle de campos y BusinessExceptionMapper
  - InvoiceService: validación de frontera, cálculo de totales, idempotencia, unicidad
  - Jackson configurado: SNAKE_CASE, sin timestamps para fechas, CORS habilitado
  - Jandex indexing para key49-storage (descubrimiento de beans CDI)
  - 44 tests nuevos (660 total proyecto, 0 failures)
- Webhooks con firma HMAC-SHA256 (T-019)
  - `WebhookDispatcher`: envío HTTP POST con firma HMAC-SHA256 en header `X-Key49-Signature`
  - `WebhookPayload`: record con 12 campos del evento (documentId, accessKey, status, etc.)
  - `WebhookDelivery`: entidad JPA para registro de entregas en `webhook_deliveries`
  - `WebhookDeliveryRepository`: consultas de reintentos pendientes y por documento
  - Reintentos con backoff: 10s, 60s, 300s (3 intentos máximo)
  - Headers: `X-Key49-Signature` (sha256=hex), `X-Key49-Event`, `X-Key49-Delivery`
  - `NotifyConsumer` integrado: despacha webhook al tenant tras autorización
  - Dependencia `quarkus-jackson` en key49-notify para serialización JSON
  - Jandex indexing para key49-notify (descubrimiento de beans CDI)
  - Configuración: connect-timeout-ms, read-timeout-ms, enabled (flag on/off)
  - 33 tests nuevos (693 total proyecto, 0 failures)
- Test end-to-end de factura electrónica (T-020)
  - `InvoiceEndToEndTest`: 25 tests ordenados ejercitando el flujo completo vía REST API
  - Creación de factura (202), idempotencia, consulta por ID, listado con filtros
  - Filtros: status, rango de fechas, recipient_id
  - Documento duplicado (409), validaciones de request (400): establishment, recipient, items, fecha, pago
  - XML y RIDE no disponibles para documento recién creado (404)
  - Anulación: rechazo en CREATED (409), éxito en AUTHORIZED (200), sin razón (400)
  - Autenticación requerida (401), paginación, rechazo resend-email en CREATED (409)
  - Verificación de eventos outbox (`doc.sign`) y cálculo correcto de totales
  - Fix: `InvoiceResource.downloadXml/downloadRide` convertidos a `Uni<Response>` (corrige error Vert.x EventLoop)
  - Fix: `InvoiceResource.create` cambiado de `io.vertx.mutiny.core.http.HttpServerRequest` a `io.vertx.core.http.HttpServerRequest`
  - 25 tests nuevos (718 total proyecto, 0 failures)

## [0.5.0] - 2026-04-05

### Agregado

- Cliente SOAP de Recepción del SRI (T-011)
  - `SriReceptionClient`: envío de XML firmado (Base64) a `validarComprobante`
  - Circuit breaker (5 fallos, 30s delay) y timeout (8s) con MicroProfile FT
  - `SriReceptionResponseParser`: parser SOAP (RECIBIDA/DEVUELTA)
  - Modelos: `ReceptionStatus`, `SriMessage`, `SriReceptionResponse`
  - `SriEndpoints`: URLs por ambiente (TEST/PRODUCTION)
  - HttpClient nativo con connect timeout 3s, read timeout 5s
  - 40 tests unitarios
- Cliente SOAP de Autorización del SRI (T-012)
  - `SriAuthorizationClient`: consulta por clave de acceso (49 dígitos) a `autorizacionComprobante`
  - Circuit breaker (5 fallos, 30s delay) y timeout (8s) con MicroProfile FT
  - `SriAuthorizationResponseParser`: parser SOAP (AUTORIZADO/NO AUTORIZADO)
  - Extracción de XML autorizado, número y fecha de autorización
  - Modelos: `AuthorizationStatus`, `SriAuthorizationResponse`
  - Validación de longitud de clave de acceso en frontera
  - 40 tests unitarios (total 80 tests SRI)
- Pipeline de procesamiento en colas (T-013)
  - `SignConsumer`: genera XML + clave de acceso + firma XAdES-BES (CREATED → SIGNED)
  - `SendConsumer`: envío SOAP Recepción SRI (SIGNED → SENT → RECEIVED | REJECTED | RETRY)
  - `AuthorizeConsumer`: consulta SOAP Autorización SRI (RECEIVED → AUTHORIZED | REJECTED | RETRY)
  - `NotifyConsumer`: stub transición (AUTHORIZED → NOTIFIED), pendiente T-015/T-016/T-017
  - `DlqConsumer`: handler terminal Dead Letter Queue (→ FAILED)
  - Outbox pattern: `OutboxEvent` entity, `OutboxRepository`, `OutboxPoller` (500ms), `OutboxCleanup` (cron 02:00 ECT), `OutboxEventRouter`
  - `InvoiceDataMapper`: mapeo Document + Tenant + requestPayload JSON → InvoiceData
  - `DocumentEvent.fromOutbox()` factory method
  - `TenantRepository.findAllActive()` para iteración multi-tenant del outbox poller
  - Máquina de estados enforced con `canTransitionTo()` en cada consumer
  - 38 tests nuevos en key49-queue (508 total proyecto, 0 failures)
- Lógica de reintentos con backoff exponencial (T-014)
  - `RetryDelayCalculator`: delays ×3 (5s, 15s, 45s, 135s, 405s)
  - `RetryPoller`: job @Scheduled(5s) que re-encola docs RETRY con `nextRetryAt` vencido
  - Determinación de tipo de reintento por `sriSubmissionDate` (doc.send o doc.authorize)
  - `SendConsumer`/`AuthorizeConsumer`: verificación de agotamiento de reintentos → FAILED
  - Cálculo de `nextRetryAt` con backoff exponencial en cada consumer
  - Transición RETRY → AUTHORIZED agregada al state machine
  - Configuración `key49.retry.poll-interval` en application.properties
  - 22 tests nuevos (531 total proyecto, 0 failures)

## [0.4.0] - 2026-04-05

### Agregado

- Generador de clave de acceso de 49 dígitos (T-006)
  - Algoritmo módulo 11 para dígito verificador
  - Validación de `issue_date` == fecha actual (zona Ecuador)
  - Validación de formatos: establishment (3 díg), issue_point (3 díg), sequence_number (9 díg)
  - 62 tests unitarios
- XML Builder de Factura conforme a XSD v2.1.0 (T-007)
  - Mapeo DTO API → estructura XML SRI (infoTributaria, infoFactura, detalles, pagos)
  - XSDs del SRI incluidos en resources
  - 38 tests unitarios
- Validador XSD dinámico por tipo de documento (T-008)
  - Carga dinámica de XSD por `DocumentType`
  - Captura y mapeo de errores a mensajes legibles
  - Cache concurrente de schemas compilados
  - 27 tests unitarios
- Firma digital XAdES-BES para comprobantes electrónicos (T-009)
  - Firma enveloped XML-DSig con Apache Santuario 4.0.4
  - RSA-SHA1, C14N inclusive, esquema XAdES-BES 1.3.2
  - Carga de certificado .p12 con BouncyCastle 1.80
  - Certificado de pruebas auto-generado
  - 25 tests unitarios
- Cifrado/descifrado AES-256-GCM de certificados (T-010)
  - `CertificateEncryptor`: encrypt/decrypt de bytes y passwords
  - IV aleatorio de 12 bytes por operación, tag de autenticación 128 bits
  - Utilidades: `generateMasterKey`, `decodeMasterKey` (Base64 desde env var)
  - Test integración round-trip: cifrar → descifrar → firmar
  - 28 tests unitarios

## [0.3.0] - 2026-04-05

### Agregado

- Configuración de RabbitMQ con SmallRye Reactive Messaging (T-004)
  - Exchange `key49.documents` (topic) con colas: sign, send, authorize, notify
  - Exchange `key49.dlq` (fanout) con cola de dead letter
  - Record `DocumentEvent` como payload de mensajes
  - `DocumentEventProducer` con 4 canales de salida
  - 5 consumers skeleton: Sign, Send, Authorize, Notify, DLQ
  - 5 tests unitarios
- Autenticación por API Key con filtro reactivo (T-005)
  - `ApiKeyService`: generación de claves (fec*test*/fec*live*), SHA-256, validación
  - `ApiKeyAuthFilter`: @ServerRequestFilter con PgPool directo (JOIN api_keys+tenants)
  - Paths públicos excluidos: /q/, /portal/login, /openapi, /swagger-ui
  - Actualización asíncrona de `last_used_at` (fire-and-forget)
  - 15 tests unitarios + 5 tests de integración
- Validaciones de formato en frontera SRI (T-005a)
  - `SriValidator`: validación RUC (módulo 11), cédula (módulo 10)
  - Validadores de establishment (3 díg), issue_point (3 díg), sequence_number (9 díg)
  - Validación de identificación por tipo SRI (RUC, cédula, pasaporte, consumidor final)
  - Bean Validation annotations: `@ValidRuc`, `@ValidCedula`, `@ValidSriCode`
  - `ValidationExceptionMapper` con formato estándar de errores y detalles por campo
  - 67 tests unitarios (SriValidatorTest + BeanValidationAnnotationTest)

## [0.2.0] - 2026-04-05

### Agregado

- Schema-per-tenant con aislamiento por esquema PostgreSQL (T-002)
  - `TenantSchemaResolver`: resolución dinámica de esquema por UUID
  - `TenantContext`: CDI bean request-scoped para tenant activo
  - `TenantConnectionManager`: gestión de `SET search_path` por request
  - Scripts SQL de migración para esquemas tenant
  - 20 tests unitarios + 9 tests de integración
- Modelo de dominio y catálogos SRI (T-003)
  - 7 enums SRI: DocumentType, DocumentStatus, TaxType, VatRate, PaymentMethod, IdentificationType, SriEnvironment
  - 3 entidades JPA: Tenant, ApiKey, Document (con machine state)
  - 3 repositorios Panache reactivos: TenantRepository, ApiKeyRepository, DocumentRepository
  - State machine con `canTransitionTo()` y `InvalidStateTransitionException`
  - 114 tests unitarios + 9 tests de integración

## [0.1.0] - 2026-04-05

### Agregado

- Inicialización del proyecto Quarkus multi-módulo con Maven (T-001)
- Estructura de 10 módulos: api, core, xml, signer, sri, queue, ride, notify, storage, admin
- Parent POM con Quarkus 3.34.2 BOM y dependency management
- Perfiles Maven: dev, test, prod
- Configuración base en `application.properties`
- Documentación técnica organizada en `docs/`
- README.md informativo del proyecto
- .gitignore configurado

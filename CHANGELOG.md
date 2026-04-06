# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/),
y este proyecto adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [No publicado]

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
- Generador de RIDE (PDF) para factura electrónica (T-015)
  - `InvoiceRideGenerator`: generación programática con OpenPDF 2.0.3
  - Secciones: encabezado emisor, datos receptor, tabla de ítems, impuestos, totales, pagos, info adicional
  - `QrCodeGenerator`: código QR con clave de acceso (ZXing 3.5.3)
  - Logo del emisor opcional (PNG/JPEG)
  - Marca de agua "SIN AUTORIZACIÓN" para documentos no autorizados
  - `RideData`: record con datos de presentación para el RIDE
  - `RideGenerationException`: excepción específica del módulo
  - 31 tests nuevos (562 total proyecto, 0 failures)

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

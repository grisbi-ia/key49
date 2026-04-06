# Plan de Desarrollo — Key49

## Fase 1: MVP — Factura Electrónica (6-8 semanas)

El objetivo es un flujo completo de factura electrónica para un solo tenant (AURACORE) en ambiente de pruebas del SRI.

### Sprint 1: Fundamentos (Semana 1-2)

- [x] **T-001** Inicializar proyecto Quarkus multi-módulo con Maven ✓
  - Parent POM con dependency management
  - Módulos: key49-api, key49-core, key49-xml, key49-signer, key49-sri, key49-queue, key49-ride, key49-notify, key49-storage, key49-admin
  - Configurar profiles: dev, test, prod
  - Archivo: `pom.xml`, módulos, `application.properties`

- [x] **T-002** Crear scripts SQL y configurar PostgreSQL schema-per-tenant ✓
  - Crear scripts SQL de referencia en `db/migrations/public/` (tenants, api_keys)
  - Crear scripts SQL de referencia en `db/migrations/tenant/` (documents, outbox, webhook_deliveries, audit_log)
  - Configurar Hibernate Reactive con Panache (estrategia SCHEMA)
  - Implementar `TenantSchemaResolver` que ejecuta `SET search_path TO 'tenant_{uuid_short}', public;`
  - Validar `schemaName` contra `[a-z0-9_]+` para prevenir SQL injection
  - NO configurar Flyway automático — las migraciones son manuales
  - Test: verificar que cada esquema de tenant aísla datos correctamente

- [x] **T-003** Implementar modelo de dominio (key49-core) ✓
  - Entidades JPA: Tenant, ApiKey, Document
  - Repositorios Panache para cada entidad
  - Enums SRI: DocumentType, DocumentStatus (con `canTransitionTo()`), TaxType, VatRate, PaymentMethod, IdentificationType, SriEnvironment
  - Constante `Key49Constants.EC_ZONE = ZoneId.of("America/Guayaquil")`
  - Excepción `InvalidStateTransitionException` para transiciones inválidas
  - Test: CRUD básico de entidades, transiciones de estado válidas/inválidas

- [x] **T-004** Configurar RabbitMQ con SmallRye Reactive Messaging ✓
  - Definir exchanges: key49.documents, key49.retry, key49.dlq
  - Definir colas según ARCHITECTURE.md
  - Implementar productores y consumidores base (skeleton)
  - Test: publicar y consumir mensaje simple

- [x] **T-005** Implementar autenticación por API Key ✓
  - Generación de API keys con prefijo (fec*test*, fec*live*)
  - Hash SHA-256 para almacenamiento
  - Filter JAX-RS que extrae API key, valida, y setea tenant context
  - Propagación del tenant al search_path de PostgreSQL (SET search_path)
  - Cache de tenant config en Redis (TTL 300s, fallback a BD)
  - Test: request con API key válido/inválido

- [x] **T-005a** Implementar validaciones de formato en frontera (key49-api) ✓
  - Validadores de RUC (módulo 11) y cédula (módulo 10)
  - Validación de `establishment` (3 dígitos), `issue_point` (3 dígitos), `sequence_number` (9 dígitos)
  - Validación de `issue_date = LocalDate.now(EC_ZONE)`
  - Validación de `tax.code`, `payment_method`, `recipient.id_type` contra enums SRI
  - Bean Validation custom annotations: `@ValidRuc`, `@ValidCedula`, `@ValidSriCode`
  - Exception mapper que retorna errores con formato estándar (ver catálogo en API.md)
  - Test: validar cada campo con valores correctos e incorrectos

### Sprint 2: Generación XML y Firma (Semana 3-4)

- [x] **T-006** Implementar generador de clave de acceso (key49-xml) ✓
  - Algoritmo módulo 11 para dígito verificador
  - Estructura completa de 49 dígitos
  - El secuencial (`sequence_number`) lo proporciona el cliente en su request
  - Validar que `issue_date` sea la fecha del día actual (`LocalDate.now(EC_ZONE)`)
  - Validar formato: `establishment` 3 díg., `issue_point` 3 díg., `sequence_number` 9 díg.
  - Test: generar 1000 claves y verificar unicidad y checksum

- [x] **T-007** Implementar XML Builder de Factura (key49-xml) ✓
  - Builder que genera XML conforme a XSD factura v2.1.0
  - Mapeo de DTO de API → estructura XML del SRI
  - Nodos: infoTributaria, infoFactura, detalles, infoAdicional, pagos
  - Incluir los XSD del SRI en resources
  - Test: generar XML y validar contra XSD

- [x] **T-008** Implementar validador XSD (key49-xml) ✓
  - Validación de XML generado contra esquema XSD correspondiente
  - Captura y mapeo de errores de validación a mensajes legibles
  - Carga dinámica de XSD por tipo y versión de documento
  - Test: XML válido pasa, XML con errores falla con mensaje claro

- [x] **T-009** Implementar firma XAdES-BES (key49-signer) ✓
  - Carga de certificado .p12 con contraseña
  - Firma enveloped con Apache Santuario
  - Configuración: XAdES-BES, esquema XAdES 1.3.2, UTF-8
  - Nodo padre: "comprobante"
  - Inserción de ds:Signature en el XML
  - Test: firmar XML con certificado de pruebas, verificar firma

- [x] **T-010** Implementar cifrado/descifrado de certificados (key49-signer) ✓
  - CertificateEncryptor con AES-256-GCM
  - Cifrar .p12 y contraseña al almacenar
  - Descifrar al momento de firmar
  - Clave maestra desde variable de entorno
  - Test: round-trip cifrar → descifrar → firmar exitosamente

### Sprint 3: Integración SRI (Semana 5-6)

- [x] **T-011** Implementar cliente SOAP de Recepción (key49-sri) ✓
  - Consumir WSDL RecepcionComprobantesOffline
  - Enviar XML firmado codificado en base64
  - Parsear respuesta: RECIBIDA / DEVUELTA con mensajes
  - Configurar timeouts: connect=3s, read=5s
  - Circuit breaker con MicroProfile Fault Tolerance
  - Test: enviar factura firmada al ambiente de pruebas del SRI

- [x] **T-012** Implementar cliente SOAP de Autorización (key49-sri) ✓
  - Consumir WSDL AutorizacionComprobantesOffline
  - Consultar por clave de acceso
  - Parsear respuesta: AUTORIZADO / NO AUTORIZADO
  - Extraer XML autorizado y número de autorización
  - Test: consultar autorización de factura enviada en T-011

- [x] **T-013** Implementar pipeline de procesamiento en colas (key49-queue)
  - Consumer `SignConsumer`: recibe doc CREATED → genera XML + clave acceso → firma → publica a doc.send
  - Consumer `SendConsumer`: envía al SRI → actualiza estado → publica a doc.authorize o doc.retry
  - Consumer `AuthorizeConsumer`: polling de autorización → publica a doc.notify o doc.retry
  - Consumer `NotifyConsumer`: genera RIDE, envía email, dispara webhook, actualiza `email_sent_at`/`email_status`
  - Implementar lógica de reintentos con backoff exponencial
  - Dead Letter Queue handler
  - **Outbox Poller**: implementar `@Scheduled` cada 500ms, batch 50, FIFO (ver ARCHITECTURE.md)
  - **Outbox Cleanup**: job nocturno que elimina eventos publicados > 7 días
  - Validar transiciones de estado con `DocumentStatus.canTransitionTo()` en cada consumer
  - Test: flujo completo end-to-end con SRI de pruebas

- [x] **T-014** Implementar lógica de reintentos (key49-queue)
  - Clasificación de errores: retriable vs definitivo
  - Backoff exponencial: 5s, 15s, 45s, 135s, 405s
  - Uso de TTL en mensajes de RabbitMQ para delays
  - Contador de reintentos y tope máximo (6)
  - Transición a DLQ cuando se agotan reintentos
  - Test: simular fallo de SRI y verificar reintentos

### Sprint 4: RIDE, Email y API REST (Semana 7-8)

- [x] **T-015** Implementar generador de RIDE (key49-ride)
  - Generar PDF de factura con formato SRI
  - Incluir: datos emisor, receptor, detalle, totales, impuestos, pagos
  - Código QR con clave de acceso
  - Logo del emisor (si está configurado)
  - Marca de agua si no está autorizado
  - Test: generar RIDE y verificar contenido visualmente

- [x] **T-016** Implementar almacenamiento en MinIO (key49-storage) ✓
  - Guardar XML sin firmar, XML firmado, XML autorizado, RIDE
  - Estructura de paths: `{tenant_id}/{year}/{month}/{doc_type}/{access_key}/`
  - Política de retención: 7 años (configurar lifecycle en MinIO)
  - Test: upload y download de archivos

- [x] **T-017** Implementar servicio de email (key49-notify) ✓
  - Template Qute para email de entrega de factura
  - Adjuntar RIDE (PDF) y XML autorizado
  - Configurar SMTP
  - Manejo de múltiples destinatarios (receptor_email con ;)
  - Test: enviar email con adjuntos

- [x] **T-018** Implementar endpoints REST de Factura (key49-api)
  - POST /invoices — crear factura
  - GET /invoices/:id — consultar factura
  - GET /invoices — listar con filtros (status, date, recipient_id, access_key, document_type) y paginación
  - GET /invoices/:id/xml — descargar XML
  - GET /invoices/:id/ride — descargar RIDE
  - POST /invoices/:id/resend-email — reenviar email
  - POST /invoices/:id/void — anular localmente (validar estado, plazo, consumidor final)
  - Validaciones de formato en frontera: RUC (mod 11), cédula (mod 10), establishment, issue_point, sequence_number
  - Validar `issue_date = LocalDate.now(EC_ZONE)`
  - Validar `tax.code`, `payment_method`, `recipient.id_type` contra enums SRI
  - Headers de respuesta: X-Request-Id, X-Trace-Id
  - Documentación OpenAPI automática
  - Test: integration tests con Quarkus @QuarkusTest

- [x] **T-019** Implementar webhooks (key49-notify)
  - Dispatcher de webhooks con firma HMAC-SHA256
  - Reintentos: 3 intentos con backoff 10s, 60s, 300s
  - Registro de entregas en webhook_deliveries
  - Test: disparar webhook y verificar firma

- [ ] **T-020** Test end-to-end completo
  - POST /invoices → verificar 202
  - Esperar webhook document.authorized
  - GET /invoices/:id → verificar status AUTHORIZED
  - GET /invoices/:id/xml → verificar XML autorizado
  - GET /invoices/:id/ride → verificar PDF generado
  - Todo contra ambiente de pruebas del SRI

---

## Fase 2: Multi-Tenant y Producción (4 semanas)

- [ ] **T-021** Implementar gestión de tenants
  - CRUD de tenants en esquema público (admin)
  - Registro de tenant = INSERT en public.tenants con campo `schema_name`
  - La creación del esquema PostgreSQL y sus tablas es manual (DBA)
  - Upload de certificado .p12 vía API
  - Verificación y extracción de metadata del certificado
  - Auto-detección de expiración

- [ ] **T-022** Implementar endpoints de tenant profile
  - GET/PUT /tenant/profile
  - POST /tenant/certificate
  - GET /tenant/certificate/status

- [ ] **T-023** Implementar gestión de API keys
  - Generación, listado, revocación
  - Múltiples API keys por tenant

- [ ] **T-025** Dashboard de métricas básico
  - GET /metrics/summary
  - Integración con Micrometer + Prometheus
  - Métricas custom: documentos procesados, tasa de error, latencia SRI

- [ ] **T-026** Rate limiting con Redis
  - Sliding window rate limiter
  - Configuración por tenant
  - Headers X-RateLimit-\*

- [ ] **T-027** Health checks y observabilidad
  - Readiness: PostgreSQL, RabbitMQ, MinIO, Redis
  - Liveness: SRI Recepción, SRI Autorización
  - Certificados próximos a vencer (warning < 30 días)
  - Notificación proactiva: enviar email y webhook `certificate.expiring` cuando un certificado vence en < 30 días
  - Configurar OpenTelemetry (`quarkus-opentelemetry`): tracing automático HTTP, JDBC, RabbitMQ
  - Headers `X-Request-Id` y `X-Trace-Id` en todas las respuestas
  - Propagación de trace context en mensajes RabbitMQ
  - Desarrollo: logs a consola con trace/span IDs
  - Producción: exportar a Grafana Tempo via OTLP

- [ ] **T-028** Configuración de producción
  - Switch de endpoints SRI (pruebas → producción)
  - Configuración de DokPloy + Traefik
  - TLS, CORS, security headers
  - Backup automático de PostgreSQL (por esquema) y MinIO
  - Documentar RPO/RTO y procedimiento de restore

- [ ] **T-028a** Implementar portal web de consulta (key49-api, /portal/)
  - Server-side rendering con Qute (templates HTML)
  - Assets estáticos: Pico CSS (estilos sin clases) + HTMX (interactividad mínima)
  - Autenticación: login con API key del tenant, sesión por cookie
  - Pantalla login: formulario simple con API key
  - Pantalla dashboard: tabla de documentos con filtros (fecha, estado, receptor) + paginación
  - Pantalla detalle: estado actual, timeline de procesamiento, datos resumen, links descarga XML/RIDE
  - Polling automático del estado con HTMX (sin JavaScript manual)
  - El portal es solo lectura — no permite crear ni modificar documentos
  - Paquete: `auracore.key49.api.portal`
  - Templates: `src/main/resources/templates/portal/`
  - Test: login con API key válido/inválido, ver lista, ver detalle

- [ ] **T-028b** Implementar endpoint XML Raw (key49-api, key49-xml)
  - POST /v1/documents/raw — recibir XML pre-armado (ver ADR-006)
  - Validar XML contra XSD correspondiente según header `X-Document-Type`
  - Parser XML → extraer datos del receptor y totales para persistir en tabla `documents`
  - **No se persisten ítems ni pagos** en tablas separadas — se preservan en `original_xml` y MinIO
  - Generar clave de acceso (Key49 siempre la genera, reemplaza la del XML)
  - Inyectar clave de acceso en el XML antes de firmar
  - Continuar pipeline normal: firmar → enviar → autorizar → notificar
  - Columna `request_origin = 'XML_RAW'` en tabla documents
  - Almacenar XML original del cliente en columna `original_xml`
  - GET /v1/documents/raw/:id — consultar estado
  - Test: enviar factura XML válida, verificar flujo completo
  - Test: enviar XML inválido, verificar error XSD_VALIDATION_FAILED
  - Test: enviar con X-Document-Type incorrecto, verificar DOCUMENT_TYPE_MISMATCH

---

## Fase 3: Documentos Adicionales (4 semanas)

- [ ] **T-029** Nota de Crédito (tipo 04, XSD v1.1.0)
- [ ] **T-030** Nota de Débito (tipo 05, XSD v1.0.0)
- [ ] **T-031** Comprobante de Retención ATS (tipo 07, XSD v2.0.0)
- [ ] **T-032** Guía de Remisión (tipo 06, XSD v1.1.0)
- [ ] **T-033** Liquidación de Compra (tipo 03, XSD v1.1.0)
- [ ] **T-034** RIDE templates para cada tipo de documento
- [ ] **T-035** Tests end-to-end para cada tipo

---

## Fase 4: Escala y Monitoreo (3 semanas)

- [ ] **T-036** Grafana dashboards
  - Documentos por estado (gauge)
  - Throughput (rate)
  - Latencia SRI P50/P95/P99 (histogram)
  - Errores por tipo (counter)
  - Cola depths (gauge)

- [ ] **T-037** Alertas
  - SRI no responde > 5 min
  - DLQ con mensajes > 0
  - Certificado expira < 30 días
  - Error rate > 5%
  - Cola depth > 1000

- [ ] **T-038** Documentación para desarrolladores
  - Landing page con quickstart
  - Guía de integración paso a paso
  - Ejemplos en curl, Python, Node.js, Java
  - Catálogo de errores con soluciones

- [ ] **T-039** SDK básico (opcional)
  - Cliente Java (para integradores Quarkus/Spring)
  - Cliente Node.js (npm package)

---

## Criterios de Aceptación Generales

1. Cada task debe tener tests unitarios (>80% coverage del módulo)
2. Los integration tests usan Quarkus DevServices (PostgreSQL, RabbitMQ, Redis en containers)
3. El código sigue las convenciones del archivo CONVENTIONS.md
4. Cada PR debe pasar CI (build + tests + linting)
5. Los endpoints REST tienen documentación OpenAPI

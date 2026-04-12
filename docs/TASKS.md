# Plan de Desarrollo — Key49

## Fase 1: MVP — Factura Electrónica (6-8 semanas)

El objetivo es un flujo completo de factura electrónica para un solo tenant (AURACORE) en ambiente de pruebas del SRI.

### Sprint 1: Fundamentos (Semana 1-2)

- [x] **T-001** Inicializar proyecto Quarkus con Maven ✓
  - POM único (packaging `jar`) con dependency management
  - Paquetes: api, core, xml, signer, sri, queue, ride, notify, storage, admin
  - Configurar profiles: dev, test, prod
  - Archivo: `pom.xml`, `application.properties`

- [x] **T-002** Crear scripts SQL y configurar PostgreSQL schema-per-tenant ✓
  - Crear scripts SQL de referencia en `db/migrations/public/` (tenants, api_keys)
  - Crear scripts SQL de referencia en `db/migrations/tenant/` (documents, outbox, webhook_deliveries, audit_log)
  - Configurar Hibernate Reactive con Panache (estrategia SCHEMA)
  - Implementar `TenantSchemaResolver` que ejecuta `SET search_path TO 'tenant_{uuid_short}', public;`
  - Validar `schemaName` contra `[a-z0-9_]+` para prevenir SQL injection
  - NO configurar Flyway automático — las migraciones son manuales
  - Test: verificar que cada esquema de tenant aísla datos correctamente

- [x] **T-003** Implementar modelo de dominio (core) ✓
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

- [x] **T-005a** Implementar validaciones de formato en frontera (api) ✓
  - Validadores de RUC (módulo 11) y cédula (módulo 10)
  - Validación de `establishment` (3 dígitos), `issue_point` (3 dígitos), `sequence_number` (9 dígitos)
  - Validación de `issue_date = LocalDate.now(EC_ZONE)`
  - Validación de `tax.code`, `payment_method`, `recipient.id_type` contra enums SRI
  - Bean Validation custom annotations: `@ValidRuc`, `@ValidCedula`, `@ValidSriCode`
  - Exception mapper que retorna errores con formato estándar (ver catálogo en API.md)
  - Test: validar cada campo con valores correctos e incorrectos

### Sprint 2: Generación XML y Firma (Semana 3-4)

- [x] **T-006** Implementar generador de clave de acceso (xml) ✓
  - Algoritmo módulo 11 para dígito verificador
  - Estructura completa de 49 dígitos
  - El secuencial (`sequence_number`) lo proporciona el cliente en su request
  - Validar que `issue_date` sea la fecha del día actual (`LocalDate.now(EC_ZONE)`)
  - Validar formato: `establishment` 3 díg., `issue_point` 3 díg., `sequence_number` 9 díg.
  - Test: generar 1000 claves y verificar unicidad y checksum

- [x] **T-007** Implementar XML Builder de Factura (xml) ✓
  - Builder que genera XML conforme a XSD factura v2.1.0
  - Mapeo de DTO de API → estructura XML del SRI
  - Nodos: infoTributaria, infoFactura, detalles, infoAdicional, pagos
  - Incluir los XSD del SRI en resources
  - Test: generar XML y validar contra XSD

- [x] **T-008** Implementar validador XSD (xml) ✓
  - Validación de XML generado contra esquema XSD correspondiente
  - Captura y mapeo de errores de validación a mensajes legibles
  - Carga dinámica de XSD por tipo y versión de documento
  - Test: XML válido pasa, XML con errores falla con mensaje claro

- [x] **T-009** Implementar firma XAdES-BES (signer) ✓
  - Carga de certificado .p12 con contraseña
  - Firma enveloped con Apache Santuario
  - Configuración: XAdES-BES, esquema XAdES 1.3.2, UTF-8
  - Nodo padre: "comprobante"
  - Inserción de ds:Signature en el XML
  - Test: firmar XML con certificado de pruebas, verificar firma

- [x] **T-010** Implementar cifrado/descifrado de certificados (signer) ✓
  - CertificateEncryptor con AES-256-GCM
  - Cifrar .p12 y contraseña al almacenar
  - Descifrar al momento de firmar
  - Clave maestra desde variable de entorno
  - Test: round-trip cifrar → descifrar → firmar exitosamente

### Sprint 3: Integración SRI (Semana 5-6)

- [x] **T-011** Implementar cliente SOAP de Recepción (sri) ✓
  - Consumir WSDL RecepcionComprobantesOffline
  - Enviar XML firmado codificado en base64
  - Parsear respuesta: RECIBIDA / DEVUELTA con mensajes
  - Configurar timeouts: connect=3s, read=5s
  - Circuit breaker con MicroProfile Fault Tolerance
  - Test: enviar factura firmada al ambiente de pruebas del SRI

- [x] **T-012** Implementar cliente SOAP de Autorización (sri) ✓
  - Consumir WSDL AutorizacionComprobantesOffline
  - Consultar por clave de acceso
  - Parsear respuesta: AUTORIZADO / NO AUTORIZADO
  - Extraer XML autorizado y número de autorización
  - Test: consultar autorización de factura enviada en T-011

- [x] **T-013** Implementar pipeline de procesamiento en colas (queue)
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

- [x] **T-014** Implementar lógica de reintentos (queue)
  - Clasificación de errores: retriable vs definitivo
  - Backoff exponencial: 5s, 15s, 45s, 135s, 405s
  - Uso de TTL en mensajes de RabbitMQ para delays
  - Contador de reintentos y tope máximo (6)
  - Transición a DLQ cuando se agotan reintentos
  - Test: simular fallo de SRI y verificar reintentos

### Sprint 4: RIDE, Email y API REST (Semana 7-8)

- [x] **T-015** Implementar generador de RIDE (ride)
  - Generar PDF de factura con formato SRI
  - Incluir: datos emisor, receptor, detalle, totales, impuestos, pagos
  - Código QR con clave de acceso
  - Logo del emisor (si está configurado)
  - Marca de agua si no está autorizado
  - Test: generar RIDE y verificar contenido visualmente

- [x] **T-016** Implementar almacenamiento en MinIO (storage) ✓
  - Guardar XML sin firmar, XML firmado, XML autorizado, RIDE
  - Estructura de paths: `{tenant_id}/{year}/{month}/{doc_type}/{access_key}/`
  - Política de retención: 7 años (configurar lifecycle en MinIO)
  - Test: upload y download de archivos

- [x] **T-017** Implementar servicio de email (notify) ✓
  - Template Qute para email de entrega de factura
  - Adjuntar RIDE (PDF) y XML autorizado
  - Configurar SMTP
  - Manejo de múltiples destinatarios (receptor_email con ;)
  - Test: enviar email con adjuntos

- [x] **T-018** Implementar endpoints REST de Factura (api)
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

- [x] **T-019** Implementar webhooks (notify)
  - Dispatcher de webhooks con firma HMAC-SHA256
  - Reintentos: 3 intentos con backoff 10s, 60s, 300s
  - Registro de entregas en webhook_deliveries
  - Test: disparar webhook y verificar firma

- [x] **T-020** Test end-to-end completo
  - POST /invoices → verificar 202
  - Esperar webhook document.authorized
  - GET /invoices/:id → verificar status AUTHORIZED
  - GET /invoices/:id/xml → verificar XML autorizado
  - GET /invoices/:id/ride → verificar PDF generado
  - Todo contra ambiente de pruebas del SRI

---

## Fase 2: Multi-Tenant y Producción (4 semanas)

- [x] **T-021** Implementar gestión de tenants
  - CRUD de tenants en esquema público (admin)
  - Registro de tenant = INSERT en public.tenants con campo `schema_name`
  - La creación del esquema PostgreSQL y sus tablas es manual (DBA)
  - Upload de certificado .p12 vía API
  - Verificación y extracción de metadata del certificado
  - Auto-detección de expiración

- [x] **T-022** Implementar endpoints de tenant profile
  - GET/PUT /tenant/profile
  - POST /tenant/certificate
  - GET /tenant/certificate/status

- [x] **T-023** Implementar gestión de API keys
  - Generación, listado, revocación
  - Múltiples API keys por tenant

- [x] **T-025** Dashboard de métricas básico
  - GET /metrics/summary
  - Integración con Micrometer + Prometheus
  - Métricas custom: documentos procesados, tasa de error, latencia SRI

- [x] **T-026** Rate limiting con Redis
  - Sliding window rate limiter
  - Configuración por tenant
  - Headers X-RateLimit-\*

- [x] **T-027** Health checks y observabilidad
  - Readiness: PostgreSQL, RabbitMQ, MinIO, Redis
  - Liveness: SRI Recepción, SRI Autorización
  - Certificados próximos a vencer (warning < 30 días)
  - Notificación proactiva: enviar email y webhook `certificate.expiring` cuando un certificado vence en < 30 días
  - Configurar OpenTelemetry (`quarkus-opentelemetry`): tracing automático HTTP, JDBC, RabbitMQ
  - Headers `X-Request-Id` y `X-Trace-Id` en todas las respuestas
  - Propagación de trace context en mensajes RabbitMQ
  - Desarrollo: logs a consola con trace/span IDs
  - Producción: exportar a Grafana Tempo via OTLP

- [x] **T-028** Configuración de producción
  - Switch de endpoints SRI (pruebas → producción)
  - Configuración de DokPloy + Traefik
  - TLS, CORS, security headers
  - Backup automático de PostgreSQL (por esquema) y MinIO
  - Documentar RPO/RTO y procedimiento de restore

- [x] **T-028a** Implementar portal web de consulta (api, /portal/)
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

- [x] **T-028b** Implementar endpoint XML Raw (api, xml)
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

- [x] **T-029** Nota de Crédito (tipo 04, XSD v1.1.0)
- [x] **T-030** Nota de Débito (tipo 05, XSD v1.0.0)
- [x] **T-031** Comprobante de Retención ATS (tipo 07, XSD v2.0.0)
- [x] **T-032** Guía de Remisión (tipo 06, XSD v1.1.0)
- [x] **T-033** Liquidación de Compra (tipo 03, XSD v1.1.0)
- [x] **T-034** RIDE templates para cada tipo de documento
- [x] **T-035** Tests end-to-end para cada tipo

---

## Fase 4: Escala y Monitoreo (3 semanas)

- [x] **T-036** Documentación para desarrolladores
  - Landing page con quickstart
  - Guía de integración paso a paso
  - Ejemplos en curl, Python, Node.js, Java
  - Catálogo de errores con soluciones

- [x] **T-037** Alertas
  - SRI no responde > 5 min
  - DLQ con mensajes > 0
  - Certificado expira < 30 días
  - Error rate > 5%
  - Cola depth > 1000

- [x] **T-037a** Correcciones de integración (pruebas live)
  - Jandex plugin en paquetes queue y sri
  - @WithSession en schedulers y consumers RabbitMQ
  - index-dependency para paquetes queue y notify
  - Document.lastErrorMessage columnDefinition text
  - DocumentRepository.findRetryReady() parámetro Instant
  - Consumers: JsonObject + fromJson (snake_case)
  - InvoiceXmlBuilder null-safe BigDecimal
  - ConsumerErrorHandler: persistencia de errores en BD

- [x] **T-037e** Corregir firma XAdES-BES para SRI ✓ `v0.17.0`
  - Error [39] FIRMA INVALIDA: la firma generada no cumplía el estándar XAdES-BES del SRI
  - `setDefaultNamespacePrefix("ds")` — sin esto la canonicalización rompe la firma
  - IDs numéricos aleatorios (formato `Signature774366`) en lugar de UUID
  - Solo certificado firmante en X509Data (no toda la cadena)
  - Id en SignedInfo y Reference de SignedProperties
  - `Element.setIdAttributeNS()` para registro de Ids en el DOM
  - `"contenido comprobante"` en descripción del DataObjectFormat
  - Verificado: factura autorizada exitosamente por SRI en ambiente de pruebas

- [x] **T-037f** Corregir InvoiceDataMapper para SRI ✓ `v0.17.0`
  - Error [35] ARCHIVO NO CUMPLE ESTRUCTURA XML: campos de impuestos null por deserialización incorrecta
  - Reescrito con records `RawPayload`/`RawItem`/`RawTax`/`RawPayment` que coinciden con el JSON de `CreateInvoiceRequest`
  - Cálculo de campos derivados: `subtotalBeforeTax`, `taxableBase`, `amount`, `totalTaxes`
  - Verificado: factura pasa validación XSD y es aceptada por el SRI

---

## Fase 6: Correcciones de Mappers por Tipo de Documento (pruebas live)

Bugs detectados durante pruebas live con SRI: los mappers deserializan el JSON del request_payload en records cuyos campos no coinciden con los nombres reales del DTO, resultando en valores null en el XML generado. Misma causa raíz que la corregida en `InvoiceDataMapper` (T-037f).

- [x] **T-053** Corregir CreditNoteDataMapper (queue/mapper)
  - **Bug**: `PayloadTax` tiene campos `taxableBase` y `amount` que no existen en `CreateCreditNoteRequest.TaxRequest` → siempre null
  - Reescribir siguiendo patrón de InvoiceDataMapper: records `RawPayload`/`RawItem`/`RawTax` con campos que coinciden con el DTO (`code`, `rateCode`, `rate`)
  - Calcular `subtotalBeforeTax = qty * unitPrice - discount` por ítem
  - Calcular `taxableBase` y `amount` (taxableBase × rate / 100) por impuesto
  - Agregar `totalTaxes` sumando por código+porcentaje
  - Validar con XML firmado de referencia de nota de crédito real

- [x] **T-054** Corregir PurchaseClearanceDataMapper (queue/mapper)
  - **Bug crítico**: deserializa directamente en records de `PurchaseClearanceData.*` (no usa records intermedios)
  - `Tax.taxCode` espera `"tax_code"` pero el DTO envía `"code"` → null
  - `subtotalBeforeTax`, `taxableBase`, `amount`, `totalTaxes` todos null (cero cálculo)
  - Reescritura completa siguiendo patrón de InvoiceDataMapper
  - Crear records `RawPayload`/`RawItem`/`RawTax`/`RawPayment` que coinciden con `CreatePurchaseClearanceRequest`
  - Calcular todos los campos derivados
  - Validar con XML firmado de referencia

- [x] **T-055** Revisar WaybillDataMapper (queue/mapper)
  - **Bug menor**: `PayloadCarrier.rise` no existe en `CreateWaybillRequest.CarrierRequest` (tiene `email`, `phone`)
  - Corregir campo `rise` — verificar si el XML del SRI lo requiere y de dónde obtenerlo
  - Validar con XML firmado de referencia de guía de remisión real

- [x] **T-056** Validación live de DebitNoteDataMapper
  - Mapper correcto (campos coinciden, calcula impuestos)
  - Validar enviando nota de débito real al SRI de pruebas

- [x] **T-057** Validación live de WithholdingDataMapper
  - Mapper correcto (estructura diferente: el cliente provee todos los valores de impuestos)
  - Validar enviando retención real al SRI de pruebas

---

- [x] **T-037b** Integrar generación de RIDE en NotifyConsumer (queue, ride)
  - Crear `RideDataMapper`: convierte `Document + Tenant + requestPayload` → `RideData` (y variantes por tipo de documento)
  - Invocar el generador RIDE correcto según `DocumentType` (factura, nota de crédito, etc.)
  - Guardar `byte[] ridePdf` para uso posterior (email, storage)
  - Manejar fallo de RIDE como no-bloqueante (no impide transición a NOTIFIED)
  - Test: verificar que NotifyConsumer genera RIDE para documento autorizado

- [x] **T-037c** Integrar almacenamiento MinIO en NotifyConsumer (queue, storage)
  - Almacenar XML autorizado (`DocumentArtifact.AUTHORIZED_XML`) y RIDE (`DocumentArtifact.RIDE`) en MinIO
  - Actualizar `doc.authorizedXmlPath` y `doc.ridePath` con rutas retornadas por `ObjectStorageService`
  - Obtener `tenantId` del tenant para construir la ruta de storage
  - Manejar fallo de storage como no-bloqueante
  - Test: verificar que paths se guardan correctamente en el documento

- [x] **T-037d** Integrar envío de email en NotifyConsumer (queue, notify)
  - Construir `EmailData` desde `Document + Tenant + ridePdf + authorizedXml`
  - Invocar `EmailService.sendDocumentDelivery()`
  - Actualizar `doc.emailSentAt`, `doc.emailStatus` ("SENT" o "FAILED")
  - Registrar `doc.emailError` si falla (no bloquea transición a NOTIFIED)
  - Test: verificar que email se invoca con datos correctos y campos se actualizan

---

## Fase 5: Cobertura de Tests XML/XSD (2 semanas)

El objetivo es garantizar que los XML generados por cada builder cumplen al 100% con los XSD del SRI, y que los campos obligatorios son detectados correctamente por el validador cuando faltan o tienen formato inválido.

### Sprint 8: Validación XSD en Builders (Semana 1)

- [x] **T-040** Agregar validación XSD a CreditNoteXmlBuilderTest
  - Agregar nested class `XsdValidation` al test existente
  - Test: nota de crédito simple pasa validación XSD (`NotaCredito_V1.1.0.xsd`)
  - Test: nota de crédito multi-ítem pasa validación XSD
  - Test: nota de crédito mínima (sin campos opcionales) pasa validación XSD
  - Usar `XsdValidator.validate(xml, DocumentType.CREDIT_NOTE)` + `assertTrue(result.valid())`

- [x] **T-041** Agregar validación XSD a DebitNoteXmlBuilderTest
  - Agregar nested class `XsdValidation` al test existente
  - Test: nota de débito simple pasa validación XSD (`NotaDebito_V1.0.0.xsd`)
  - Test: nota de débito con múltiples motivos pasa validación XSD
  - Test: nota de débito mínima pasa validación XSD

- [x] **T-042** Agregar validación XSD a WithholdingXmlBuilderTest
  - Agregar nested class `XsdValidation` al test existente
  - Test: retención simple pasa validación XSD (`ComprobanteRetencion_V2.0.0.xsd`)
  - Test: retención con múltiples docs sustento y retenciones pasa XSD
  - Test: retención mínima pasa validación XSD

- [x] **T-043** Agregar validación XSD a WaybillXmlBuilderTest
  - Agregar nested class `XsdValidation` al test existente
  - Test: guía de remisión simple pasa validación XSD (`GuiaRemision_V1.1.0.xsd`)
  - Test: guía de remisión con múltiples destinatarios pasa XSD
  - Test: guía de remisión mínima pasa validación XSD

### Sprint 9: Tests Negativos — Campos Obligatorios Faltantes (Semana 1-2)

- [x] **T-044** Tests de campos obligatorios faltantes en Factura (XSD v2.1.0)
  - Crear clase `InvoiceXsdMandatoryFieldsTest` en `auracore.key49.xml.validation`
  - Tests parametrizados: remover cada campo obligatorio de `infoTributaria` y verificar que XSD falla:
    `ambiente`, `tipoEmision`, `razonSocial`, `ruc`, `claveAcceso`, `codDoc`, `estab`, `ptoEmi`, `secuencial`, `dirMatriz`
  - Tests parametrizados: remover cada campo obligatorio de `infoFactura` y verificar que XSD falla:
    `fechaEmision`, `obligadoContabilidad`, `tipoIdentificacionComprador`, `razonSocialComprador`,
    `identificacionComprador`, `totalSinImpuestos`, `totalDescuento`, `totalConImpuestos`,
    `propina`, `importeTotal`, `moneda`, `pagos`
  - Test: factura sin nodo `detalles` falla XSD
  - Test: detalle sin campos obligatorios (`descripcion`, `cantidad`, `precioUnitario`, `descuento`, `precioTotalSinImpuesto`, `impuestos`) falla XSD
  - Usar helper que genere XML válido y luego remueva un nodo específico con DOM API

- [x] **T-045** Tests de campos obligatorios faltantes en Nota de Crédito (XSD v1.1.0)
  - Crear clase `CreditNoteXsdMandatoryFieldsTest` en `auracore.key49.xml.validation`
  - Tests parametrizados: remover cada campo obligatorio de `infoTributaria` → falla XSD
  - Tests parametrizados: remover cada campo obligatorio de `infoNotaCredito` → falla XSD:
    `fechaEmision`, `tipoIdentificacionComprador`, `razonSocialComprador`,
    `identificacionComprador`, `obligadoContabilidad`, `codDocModificado`,
    `numDocModificado`, `fechaEmisionDocSustento`, `totalSinImpuestos`, `valorModificacion`, `moneda`
  - Test: sin nodo `detalles` falla XSD

- [x] **T-046** Tests de campos obligatorios faltantes en Nota de Débito (XSD v1.0.0)
  - Crear clase `DebitNoteXsdMandatoryFieldsTest` en `auracore.key49.xml.validation`
  - Tests parametrizados: remover cada campo obligatorio de `infoTributaria` → falla XSD
  - Tests parametrizados: remover cada campo obligatorio de `infoNotaDebito` → falla XSD:
    `fechaEmision`, `tipoIdentificacionComprador`, `razonSocialComprador`,
    `identificacionComprador`, `obligadoContabilidad`, `codDocModificado`,
    `numDocModificado`, `fechaEmisionDocSustento`, `totalSinImpuestos`, `valorTotal`
  - Test: sin nodo `motivos` falla XSD

- [x] **T-047** Tests de campos obligatorios faltantes en Retención (XSD v2.0.0)
  - Crear clase `WithholdingXsdMandatoryFieldsTest` en `auracore.key49.xml.validation`
  - Tests parametrizados: remover cada campo obligatorio de `infoTributaria` → falla XSD
  - Tests parametrizados: remover cada campo obligatorio de `infoCompRetencion` → falla XSD:
    `fechaEmision`, `obligadoContabilidad`, `tipoIdentificacionSujetoRetenido`,
    `razonSocialSujetoRetenido`, `identificacionSujetoRetenido`, `periodoFiscal`
  - Tests parametrizados: campos obligatorios de `docSustento` y `retencion` → falla XSD

- [x] **T-048** Tests de campos obligatorios faltantes en Guía de Remisión (XSD v1.1.0)
  - Crear clase `WaybillXsdMandatoryFieldsTest` en `auracore.key49.xml.validation`
  - Tests parametrizados: remover cada campo obligatorio de `infoTributaria` → falla XSD
  - Tests parametrizados: remover cada campo obligatorio de `infoGuiaRemision` → falla XSD:
    `dirPartida`, `razonSocialTransportista`, `tipoIdentificacionTransportista`,
    `rucTransportista`, `obligadoContabilidad`, `fechaIniTransporte`,
    `fechaFinTransporte`, `placa`
  - Tests parametrizados: campos obligatorios de `destinatario` → falla XSD

- [x] **T-049** Tests de campos obligatorios faltantes en Liquidación de Compra (XSD v1.1.0)
  - Crear clase `PurchaseClearanceXsdMandatoryFieldsTest` en `auracore.key49.xml.validation`
  - Tests parametrizados: remover cada campo obligatorio de `infoTributaria` → falla XSD
  - Tests parametrizados: remover cada campo obligatorio de `infoLiquidacionCompra` → falla XSD:
    `fechaEmision`, `obligadoContabilidad`, `tipoIdentificacionProveedor`,
    `razonSocialProveedor`, `identificacionProveedor`, `totalSinImpuestos`,
    `totalDescuento`, `totalConImpuestos`, `importeTotal`, `moneda`, `pagos`
  - Test: sin nodo `detalles` falla XSD

### Sprint 10: Tests de Patterns y Formatos XSD (Semana 2)

- [x] **T-050** Tests de validación de patterns XSD (restricciones regex del SRI)
  - Crear clase `XsdPatternValidationTest` en `auracore.key49.xml.validation`
  - Tests parametrizados por tipo de comprobante:
    - RUC inválido (no 13 dígitos, letras, formato incorrecto) → falla XSD
    - Establecimiento inválido (no 3 dígitos, letras) → falla XSD
    - Punto de emisión inválido (no 3 dígitos) → falla XSD
    - Secuencial inválido (no 9 dígitos) → falla XSD
    - Clave de acceso inválida (no 49 dígitos) → falla XSD
    - Código de documento inválido → falla XSD
    - Ambiente inválido (no 1 ni 2) → falla XSD
    - Tipo de emisión inválido → falla XSD
  - Usar helper que genere XML válido y reemplace un solo campo con valor inválido

- [x] **T-051** Tests negativos de XsdValidator para todos los tipos de documento
  - Ampliar `XsdValidatorTest` con nested class `InvalidXmlAllTypes`
  - Para cada `DocumentType` (CREDIT_NOTE, DEBIT_NOTE, WITHHOLDING, WAYBILL, PURCHASE_CLEARANCE):
    - Test: XML malformado → falla
    - Test: XML vacío → falla
    - Test: elemento raíz incorrecto → falla
    - Test: nodo principal ausente → falla con mensaje descriptivo
  - Actualmente estos tests solo existen para `INVOICE`

- [x] **T-052** Helper reutilizable para manipulación de XML en tests ✅ `v0.16.13`
  - Crear clase `XmlTestHelper` en `auracore.key49.xml.validation` (package test)
  - Método `removeElement(String xml, String parentTag, String childTag)` → remueve un nodo hijo del XML usando DOM API
  - Método `replaceElementValue(String xml, String tagName, String newValue)` → reemplaza el texto de un nodo
  - Método `buildValidXml(DocumentType type)` → genera un XML válido para cada tipo usando los builders existentes
  - Reutilizable por T-044 a T-051

---

## Fase 7: Producción Multi-Tenant de Alto Rendimiento (8 semanas)

Key49 será utilizado simultáneamente por múltiples empresas (Yalobox, Neogas, NeoNet, etc.) en un solo servidor. Esta fase prepara la plataforma para soportar carga concurrente de decenas de tenants con SLAs de producción: latencia predecible, aislamiento de recursos, resiliencia ante fallos externos (SRI, SMTP, MinIO) y observabilidad granular por tenant.

### Sprint 11: Tuning de Pools y Concurrencia (Semana 1)

- [x] **T-058** Configurar pool de conexiones PostgreSQL ✓
  - Definir `quarkus.datasource.jdbc.min-size` y `quarkus.datasource.jdbc.max-size` (recomendado: `max-size = (num_tenants × 2) + 10`, tope ~50)
  - Configurar `quarkus.datasource.jdbc.acquisition-timeout` (ej: 5s)
  - Configurar `quarkus.datasource.jdbc.idle-removal-interval` para liberar conexiones ociosas
  - Configurar `quarkus.datasource.jdbc.max-lifetime` para reciclar conexiones viejas
  - Validar con `quarkus.datasource.jdbc.validation-query-sql=SELECT 1`
  - Variables de entorno: `KEY49_DB_POOL_MIN`, `KEY49_DB_POOL_MAX`
  - Métrica Micrometer: `agroal.active.count`, `agroal.awaiting.count` expuestas en `/q/metrics`
  - Test: verificar que el pool se inicializa con los valores configurados

- [x] **T-059** Configurar worker threads de Quarkus ✓
  - Quarkus usa virtual threads (ya habilitado), pero verificar `quarkus.thread-pool.max-threads` para platform threads residuales
  - Configurar `quarkus.vertx.event-loops-pool-size` acorde a cores disponibles
  - Variable de entorno: `KEY49_THREAD_POOL_MAX`
  - Documentar en `DEPLOYMENT.md` la relación entre virtual threads y pool sizing
  - Test: load test local con 50 requests concurrentes, verificar que no hay thread starvation

- [x] **T-060** Configurar prefetch y concurrencia de consumers RabbitMQ
  - Añadir `mp.messaging.incoming.doc-sign-in.rabbitmq.prefetch=10` (y cada consumer)
  - Evaluar concurrencia: `mp.messaging.incoming.*.concurrency=N` si SmallRye lo soporta, o múltiples instancias del consumer
  - Prefetch diferenciado: sign=10, send=5 (SRI lento), authorize=5, notify=10
  - Variables de entorno: `KEY49_RABBITMQ_PREFETCH_SIGN`, `KEY49_RABBITMQ_PREFETCH_SEND`, etc.
  - Documentar impacto: prefetch alto = más throughput pero más memoria; bajo = menos presión sobre SRI
  - Test: publicar 100 mensajes, verificar procesamiento paralelo con prefetch > 1

- [x] **T-061** Optimizar Outbox Poller para alto throughput
  - Hacer configurable: `key49.outbox.batch-size` (default 50 → probar 200) y `key49.outbox.poll-interval` (default 500ms → probar 200ms)
  - Implementar polling adaptativo: intervalo corto cuando hay eventos, largo cuando está vacío
  - Agregar métrica: `key49.outbox.events.polled` (counter), `key49.outbox.poll.duration` (timer)
  - Evaluar SELECT ... FOR UPDATE SKIP LOCKED para concurrencia de pollers (futuro multi-instancia)
  - Test: insertar 500 eventos outbox, medir tiempo hasta que todos se publican

### Sprint 12: Caché con Redis (Semana 2-3)

- [x] **T-062** Caché de API keys en Redis
  - Actualmente cada request HTTP ejecuta `SELECT` a BD para validar API key → cuello de botella
  - Implementar caché en Redis con TTL configurable (default 5 min): `key49:apikey:{hash}` → `{tenant_id, key_id, status}`
  - Usar Quarkus Cache con `@CacheResult` o `RedisAPI` directo
  - Invalidar caché al revocar/crear API key (`@CacheInvalidate` o DEL explícito)
  - Variable de entorno: `KEY49_API_KEY_CACHE_TTL_SECONDS=300`
  - Fallback: si Redis no disponible, consultar BD directamente (degradación graceful)
  - Test: verificar cache hit (no SQL), cache miss (SQL + populate), invalidación al revocar

- [x] **T-063** Caché de metadatos de tenant
  - Cachear `Tenant` (schema_name, certificate metadata, trade_name, etc.) en Redis
  - Key: `key49:tenant:{tenant_id}` → JSON del tenant (sin certificado binario)
  - TTL: 10 minutos (los tenants cambian raramente)
  - Invalidar al actualizar perfil o certificado
  - Beneficio: evita JOIN/SELECT a `public.tenants` en cada operación del pipeline
  - Test: crear tenant, verificar cache populate, actualizar tenant, verificar invalidación

- [x] **T-064** Caché de certificados .p12 en memoria
  - Los consumers descifran el .p12 de BD en cada firma → costoso (BouncyCastle + AES)
  - Implementar caché local (Caffeine o ConcurrentHashMap con TTL) de `PrivateKey + X509Certificate` ya parseados
  - Key: `tenant_id`, TTL: 30 minutos, max entries: 100
  - Invalidar al subir nuevo certificado
  - Beneficio: evitar descifrado AES + parsing PKCS12 repetido (~50ms por operación)
  - Test: firmar 2 documentos seguidos, verificar que solo el primero parsea el .p12

### Sprint 13: Resiliencia y Tolerancia a Fallos (Semana 3-4)

- [x] **T-065** Circuit Breaker para SRI SOAP
  - Aplicar `@CircuitBreaker` (MicroProfile Fault Tolerance) a `SriReceptionClient` y `SriAuthorizationClient`
  - Parámetros: `requestVolumeThreshold=10`, `failureRatio=0.5`, `delay=30s`, `successThreshold=3`
  - Cuando el circuito está abierto: retornar error rápido sin esperar timeout del SRI (fail-fast)
  - Los mensajes van a retry queue con backoff, no se pierden
  - Métrica: `ft.circuitbreaker.state` (open/closed/half-open) exportada a Prometheus
  - Combinar con `@Timeout` existente (reception=3s, authorization=5s)
  - Test: simular 10 fallos seguidos del SRI, verificar que el circuito se abre
  - Test: verificar que tras delay el circuito pasa a half-open y se recupera

- [x] **T-066** Timeouts y Circuit Breaker para MinIO
  - `MinioClient` no tiene timeouts configurados → puede bloquear un consumer indefinidamente
  - Configurar `MinioClient.builder().connectTimeout(5, SECONDS).writeTimeout(30, SECONDS).readTimeout(15, SECONDS)`
  - Variables: `KEY49_STORAGE_CONNECT_TIMEOUT_S`, `KEY49_STORAGE_WRITE_TIMEOUT_S`, `KEY49_STORAGE_READ_TIMEOUT_S`
  - Evaluar Circuit Breaker si MinIO está caído: `@CircuitBreaker` o wrapper manual
  - Beneficio: si MinIO cae, los consumers fallan rápido y van a retry, sin bloquear threads
  - Test: simular timeout de MinIO, verificar que el consumer falla y reintenta

- [x] **T-067** Graceful shutdown con drenaje de consumers
  - Verificar que `quarkus.shutdown.timeout=30s` permite que consumers en vuelo terminen
  - Implementar hook `@Observes ShutdownEvent` que loguee consumers activos
  - Verificar que mensajes no-acked vuelven a la cola tras shutdown (RabbitMQ basic.nack con requeue)
  - Documentar procedimiento de deploy sin pérdida de mensajes: stop consumers → drain → deploy → start
  - Test: enviar mensaje a consumer, iniciar shutdown, verificar que el mensaje se procesa o se re-encola

- [x] **T-068** Backpressure y monitoreo de profundidad de cola
  - La alerta `queue-depth-max=1000` ya existe (T-037), pero no hay acción automática
  - Implementar health check que revise profundidad de todas las colas vía API de RabbitMQ management
  - Si profundidad > threshold: marcar readiness=false → el balanceador deja de enviar tráfico
  - Exponer métricas: `key49.queue.depth{queue=sign}`, `key49.queue.depth{queue=send}`, etc.
  - Variable: `KEY49_QUEUE_DEPTH_CRITICAL=5000` (readiness=false), `KEY49_QUEUE_DEPTH_WARNING=1000` (alerta)
  - Test: verificar que health check reporta DOWN cuando queue depth excede threshold

### Sprint 14: Base de Datos para Escala Multi-Tenant (Semana 4-5)

- [x] **T-069** Evaluar e implementar PgBouncer como connection pooler
  - Con N tenants, cada uno usando `SET search_path`, el pool de Agroal puede no ser suficiente
  - Desplegar PgBouncer en modo `transaction` delante de PostgreSQL
  - Configurar: `max_client_conn=200`, `default_pool_size=25`, `reserve_pool_size=5`
  - Ajustar Quarkus para conectar a PgBouncer en lugar de PostgreSQL directo
  - Documentar en `DEPLOYMENT.md` y `docker-compose.yml`
  - Test: verificar que multi-tenant funciona con PgBouncer (search_path se mantiene por transacción)

- [x] **T-070** Particionamiento de tabla `documents` por fecha
  - La tabla `documents` crecerá rápidamente (~1000 docs/día por tenant grande)
  - Implementar particionamiento por rango mensual: `documents_2025_01`, `documents_2025_02`, ...
  - Script SQL: `ALTER TABLE documents ... PARTITION BY RANGE (created_at)`
  - Crear script de mantenimiento que crea particiones futuras (cron mensual)
  - Beneficio: queries con filtro de fecha son O(partición) en lugar de O(tabla completa)
  - Agregar a `db/migrations/tenant/` con documentación
  - Test: verificar que queries con filtro de fecha usan partition pruning (EXPLAIN ANALYZE)

- [x] **T-071** Mantenimiento automatizado de PostgreSQL
  - Script `db/maintenance.sh`: VACUUM ANALYZE en todas las tablas de todos los esquemas tenant
  - Configurar `autovacuum_vacuum_scale_factor=0.05` (más agresivo que default 0.2) para tabla `documents`
  - Monitorear bloat con `pgstattuple` o estimación de dead tuples
  - Crear índice `CONCURRENTLY` script para reindexación sin downtime
  - Documentar procedimiento en `DB-ADMIN.md`

- [x] **T-072** Monitoreo de queries y optimización
  - Habilitar `pg_stat_statements` en PostgreSQL
  - Script para extraer top 10 queries más lentas y más frecuentes
  - Revisar plan de ejecución de queries del pipeline (sign, send, authorize, notify consumer)
  - Verificar que los índices existentes cubren los patrones de acceso multi-tenant
  - Agregar índice parcial: `CREATE INDEX idx_documents_pending ON documents (status) WHERE status IN ('CREATED','SIGNED','SENT','RECEIVED')` (solo docs activos)
  - Documentar hallazgos en `DB-ADMIN.md`

### Sprint 15: Seguridad y Hardening (Semana 5-6)

- [x] **T-073** Auditoría de seguridad OWASP Top 10
  - Revisar cada endpoint REST contra:
    - Injection (SQL/NoSQL/LDAP): verificar que todas las queries usan parámetros bindeados (Hibernate/Panache)
    - Broken Authentication: verificar validación de API key, session handling del portal
    - Broken Access Control: verificar aislamiento de tenant (un tenant no puede ver datos de otro)
    - Security Misconfiguration: revisar headers HTTP, CORS en producción, error messages
    - XSS: verificar escaping en templates Qute del portal
  - Herramienta: ejecutar OWASP ZAP o similar contra API en ambiente de pruebas
  - Documentar hallazgos y remediaciones en `docs/SECURITY.md`

- [x] **T-074** Rate limiting granular por endpoint ✓
  - Enum `EndpointCategory` (WRITE/READ) con mapeo desde método HTTP
  - Columnas `rate_limit_write_rpm` (default 30) y `rate_limit_read_rpm` (default 200) en `tenants`
  - `RateLimiter.checkLimit()` recibe categoría, usa Redis key `ratelimit:{prefix}:{category}`
  - `RateLimitFilter` selecciona límite según categoría del endpoint
  - DTOs `TenantResponse` y `UpdateTenantRequest` exponen los nuevos campos
  - Métrica: `key49.rate_limit.rejected{tenant=X, category=Y}` (counter)
  - Migración `V003__add_granular_rate_limits.sql`
  - Tests: `EndpointCategoryTest`, `GranularRateLimitTest`, actualizado `RateLimitEndToEndTest`

- [x] **T-075** Audit log de operaciones sensitivas
  - Registrar en tabla `public.audit_log`:
    - Login/logout del portal
    - Creación/revocación de API keys
    - Upload de certificado .p12
    - Cambio de configuración de tenant
    - Anulación de documento (VOID)
  - Campos: `audit_log_id`, `tenant_id`, `actor` (api_key o session), `action`, `resource`, `resource_id`, `ip_address`, `details` (JSONB), `created_at`
  - Script SQL en `db/migrations/public/`
  - Endpoint admin: `GET /admin/audit-log` con filtros (tenant, action, date range)
  - Test: realizar operación sensitiva, verificar registro en audit_log

- [x] **T-076** Rotación de certificados .p12 sin downtime ✅ `v0.24.3`
  - Actualmente subir un nuevo certificado reemplaza el anterior inmediatamente
  - Implementar "certificado pendiente": subir nuevo cert con `status=PENDING`, validar, activar
  - Endpoint: `POST /tenant/certificate/rotate` → sube nuevo, `POST /tenant/certificate/activate` → activa
  - Invalidar caché de certificado (.p12 en memoria, T-064) al activar
  - Ventana de gracia: durante rotación, documentos en vuelo terminan con cert viejo
  - Test: subir cert nuevo, verificar que docs en vuelo usan cert anterior, activar, verificar nuevo

### Sprint 16: Observabilidad Avanzada (Semana 6-7)

- [x] **T-077** Métricas dimensionadas por tenant ✅ `v0.25.0`
  - Agregar tag `tenant_id` a todas las métricas de negocio:
    - `key49.documents.created{tenant=X, type=INVOICE}` (counter)
    - `key49.documents.authorized{tenant=X}` (counter)
    - `key49.documents.failed{tenant=X, reason=SRI_REJECTED}` (counter)
    - `key49.sri.latency{tenant=X, operation=reception}` (timer)
    - `key49.email.sent{tenant=X}` (counter)
  - Beneficio: identificar qué tenant genera más carga, más errores, o más latencia
  - Precaución: cardinalidad alta → limitar a tenants activos, usar tag solo en métricas clave
  - Test: emitir documento, verificar que métricas tienen tag tenant_id correcto

- [x] **T-078** Alertas SLA y métricas de negocio
  - Definir SLAs por tenant (configurable):
    - Tiempo máximo de autorización: 5 minutos desde creación
    - Tasa de error máxima: 2% por hora
    - Disponibilidad del pipeline: 99.5%
  - Alerta cuando SLA es violado: `key49.sla.breach{tenant=X, type=authorization_latency}`
  - Implementar check periódico (cada 5 min): buscar docs CREATED hace > 5 min que no estén AUTHORIZED
  - Notificar vía webhook y email al equipo de operaciones
  - Test: crear documento y no procesarlo, verificar que alerta SLA se dispara tras threshold

- [x] **T-079** Structured logging con contexto de tenant
  - Agregar `tenant_id` y `document_id` al MDC (Mapped Diagnostic Context) en cada operación
  - Formato de log: `[tenant=T, doc=D, trace=T] message`
  - Configurar `quarkus.log.console.format` con campos MDC
  - Beneficio: filtrar logs por tenant en producción (`grep tenant=yalobox`)
  - Verificar que MDC se propaga en virtual threads (Quarkus context propagation)
  - Test: procesar documento, verificar que todos los logs del flujo tienen tenant_id

### Sprint 17: Funcionalidades de Valor para Producción (Semana 8)

- [x] **T-080** Retry manual desde portal web ✅ `v0.25.3`
  - Botón "Reintentar" en documentos con estado FAILED o REJECTED en la vista de detalle
  - Endpoint: `POST /portal/documents/{id}/retry`
  - Validar que el documento está en estado terminal (FAILED) antes de reintentar
  - Resetear `retry_count=0`, transicionar a CREATED, republicar en cola de firma
  - Solo disponible para documentos con `issue_date = hoy` (emisión mismo día)
  - Log: registrar quién y cuándo reintentó (audit trail)
  - Test: documento FAILED, hacer retry desde portal, verificar que entra al pipeline

- [x] **T-081** Dashboard de métricas del tenant en portal ✅ `v0.25.4`
  - Nueva página `/portal/metrics` con resumen visual para el tenant:
    - Total de documentos por estado (cards: Autorizados ✓, En proceso ⏳, Fallidos ✗)
    - Gráfico de barras: documentos emitidos por día (últimos 30 días)
    - Último documento emitido con estado actual
    - Estado del certificado (vigente, días para vencer, vencido)
  - Server-side con Qute + Pico CSS (sin JavaScript de gráficas, usar barras CSS)
  - Datos: queries agregadas a la tabla `documents` del esquema del tenant
  - Test: verificar que los contadores coinciden con datos reales

- [x] **T-082** API de consulta masiva y exportación CSV ✅ v0.25.5
  - `GET /v1/documents/export?format=csv&from=2025-01-01&to=2025-01-31&status=AUTHORIZED`
  - Streaming response (no cargar todo en memoria): usar `StreamingOutput` o `Multi<String>`
  - Campos CSV: access_key, document_type, sequence, recipient, total, status, authorized_at
  - Límite: máximo 10,000 registros por request (paginación con cursor si se excede)
  - Header `Content-Disposition: attachment; filename=key49-export-2025-01-31.csv`
  - Test: exportar 100 documentos, verificar formato CSV válido

- [x] **T-083** Notificaciones de estado del sistema por tenant ✅ v0.25.6
  - Webhook `system.maintenance` antes de ventanas de mantenimiento programado
  - Webhook `system.incident` si el SRI está caído (basado en circuit breaker, T-065)
  - Webhook `certificate.expired` (además del existente `certificate.expiring`)
  - Endpoint: `GET /v1/system/status` → estado actual del SRI, MinIO, colas
  - Beneficio: los integradores pueden pausar envíos cuando el sistema reporta problemas
  - Test: simular SRI caído, verificar que webhook system.incident se dispara

  - [x] **T-084** Docker production image optimizado ✅ v0.25.7
  - Multi-stage Dockerfile: build con Maven → runtime con JRE mínimo
  - Evaluar Quarkus native image (GraalVM) para reducir startup y memoria
  - Configurar JVM flags para producción: `-XX:MaxRAMPercentage=75`, `-XX:+UseG1GC`
  - Health check en Dockerfile: `HEALTHCHECK CMD curl -f http://localhost:8080/q/health/ready`
  - Publicar imagen en registry (Docker Hub o GHCR)
  - Documentar en `DEPLOYMENT.md`

### Tarea Intermedia: Documentación Operativa

- [x] **T-084A** Guía operativa del sistema (`docs/OPERATIONS.md`) ✅ v0.25.8
  - **Flujo end-to-end de un comprobante**: diagrama y explicación paso a paso desde el request API hasta la notificación final, indicando qué componente interviene en cada fase (API → PostgreSQL → RabbitMQ → Signer → SRI SOAP → MinIO → Email/Webhook)
  - **RabbitMQ — colas y consumers**: exchange `key49`, routing keys, cola por etapa (`sign`, `send`, `authorize`, `notify`), cola DLQ, prefetch por consumer, qué pasa si un consumer se cae
  - **Reintentos y backoff exponencial**: qué errores se reintentan (infra) vs cuáles no (negocio SRI), secuencia de delays (5s→15s→45s→135s→405s), máximo 6 intentos, transición a FAILED
  - **Máquina de estados**: diagrama completo (CREATED→SIGNED→SENT→RECEIVED→AUTHORIZED→NOTIFIED), estados de error (REJECTED, FAILED), RETRY, VOIDED, con tabla de transiciones válidas
  - **Circuit breaker SRI**: cuándo se abre (5 fallos), duración half-open (30s), qué pasa con los documentos en cola mientras está abierto, recovery automático
  - **Redis — caché y resiliencia**: qué se cachea (API keys, tenants, certificados), TTL de cada tipo, comportamiento cuando Redis no está disponible (fallback a BD)
  - **MinIO — almacenamiento de artefactos**: qué se guarda (XML firmado, XML autorizado, RIDE PDF), estructura de buckets/paths, qué pasa si MinIO está caído
  - **PgBouncer — pool de conexiones**: modo transacción, `SET LOCAL search_path`, sizing del pool, relación con multi-tenancy
  - **Outbox pattern**: cómo garantiza exactly-once delivery, frecuencia de polling, limpieza de registros antiguos (7 días)
  - **Resiliencia ante caídas**: tabla de escenarios (PostgreSQL caído, RabbitMQ caído, Redis caído, MinIO caído, SRI caído) con el impacto y la recuperación automática de cada uno
  - **Webhooks**: flujo de entrega, HMAC-SHA256, reintentos de entrega, validación SSRF de URLs
  - **Idempotencia**: cómo funciona `X-Idempotency-Key`, dónde se almacena, TTL, comportamiento ante requests duplicados

### Sprint 18: Grafana y Observabilidad Avanzada (Semana 7-8)

- [ ] **T-085** Grafana dashboards
  - Documentos por estado (gauge)
  - Throughput (rate)
  - Latencia SRI P50/P95/P99 (histogram)
  - Errores por tipo (counter)
  - Cola depths (gauge)
- [ ] **T-085A** Grafana dashboards (complementa T-080)
  - Dashboard "Overview": documentos por estado (gauge), throughput global (rate), latencia P50/P95/P99
  - Dashboard "Per-Tenant": métricas desglosadas por tenant_id, comparación entre tenants
  - Dashboard "Infrastructure": pool de conexiones BD, queue depth, Redis hit rate, MinIO latencia
  - Dashboard "Alerts": historial de alertas, SLA breaches, certificados por vencer
  - Exportar dashboards como JSON en `infra/grafana/dashboards/`
  - Documentar setup de Grafana + Prometheus en `DEPLOYMENT.md`

### Sprint 19: Load Testing y Sizing (Semana 7-8)

- [ ] **T-086** Scripts de load testing
  - Crear scripts k6 o Gatling en `tests/load/`:
    - Escenario 1: 1 tenant, 100 facturas/min durante 10 min
    - Escenario 2: 5 tenants simultáneos, 50 facturas/min cada uno durante 10 min
    - Escenario 3: Pico de carga: 10 tenants, 200 facturas/min total durante 5 min
    - Escenario 4: Consulta masiva: 1000 GET /invoices con filtros variados
  - Medir: latencia P50/P95/P99, throughput, error rate, recursos (CPU/RAM/conexiones)
  - Generar reporte con resultados baseline (antes de tuning vs después)

- [ ] **T-087** Benchmark de throughput por tipo de documento
  - Medir tiempo end-to-end (CREATED → NOTIFIED) para cada tipo de documento
  - Identificar cuello de botella por tipo: factura vs retención vs guía de remisión
  - Medir tiempos individuales: firma (~Xms), envío SRI (~Xms), autorización (~Xms), RIDE (~Xms), email (~Xms)
  - Documentar resultados en `docs/PERFORMANCE.md`
  - Establecer baselines para regresiones futuras

- [ ] **T-088** Guía de sizing y capacidad
  - Documentar en `docs/SIZING.md`:
    - Fórmulas: conexiones BD = f(tenants, concurrencia), RAM = f(tenants, caché, PDFs en vuelo)
    - Perfil "Small" (1-5 tenants, <500 docs/día): 2 vCPU, 2GB RAM, pool=10
    - Perfil "Medium" (5-20 tenants, <5000 docs/día): 4 vCPU, 4GB RAM, pool=30
    - Perfil "Large" (20-50 tenants, <20000 docs/día): 8 vCPU, 8GB RAM, pool=50
    - Requisitos de PostgreSQL, RabbitMQ, Redis, MinIO por perfil
  - Incluir diagrama de despliegue (Mermaid) con componentes y flujos de red

# Sprint 20: SDK y Documentación para Integradores (Semana 8)

- [ ] **T-089** SDK básico (opcional)
  - Cliente Java (para integradores Quarkus/Spring)
  - Cliente Node.js (npm package)

---

## Criterios de Aceptación Generales

1. Cada task debe tener tests unitarios (>80% coverage del módulo)
2. Los integration tests usan Quarkus DevServices (PostgreSQL, RabbitMQ, Redis en containers)
3. El código sigue las convenciones del archivo CONVENTIONS.md
4. Cada PR debe pasar CI (build + tests + linting)
5. Los endpoints REST tienen documentación OpenAPI

---

## Fase 8: Comercialización SaaS (6-8 semanas)

> **Objetivo**: Convertir Key49 en un producto SaaS comercializable con autoregistro, planes de documentos, control de cuota y renovación manual de pagos. El cliente puede registrarse, configurar su firma electrónica y empezar a emitir comprobantes sin intervención del administrador.

### Sprint 21: Provisioning Automático de Tenants (Semana 1-2)

- [x] **T-090** Función PL/pgSQL `clone_schema()` y esquema template ✅ v0.25.9
  - Crear esquema `tenant_template` con todas las tablas (V001–V006) vacías
  - Implementar función `clone_schema(source, target)` en PL/pgSQL que duplique tablas, índices, constraints y sequences
  - Validar que el esquema destino no exista (prevenir sobreescritura)
  - Script SQL en `db/migrations/public/` para crear la función y el template
  - Test: clonar template, verificar que las tablas existen con estructura idéntica
  - Documentar en `DB-ADMIN.md` el mantenimiento del template (aplicar migraciones nuevas)

- [x] **T-091** Provisioning automático en `TenantAdminService` ✅ v0.26.0
  - Modificar `TenantAdminService.create()` para ejecutar `SELECT clone_schema('tenant_template', :schema)` después del INSERT
  - Transición automática a `status = 'active'` tras provisioning exitoso
  - Si falla el clonado: rollback, dejar `status = 'failed'`, log de error detallado
  - Respetar validación de `TenantSchemaResolver.validate()` (inyección SQL)
  - Invalidar caché Redis del tenant tras activación
  - Test: crear tenant vía API, verificar que esquema existe y tablas son accesibles
  - Test: intentar crear tenant con schema duplicado → error controlado

### Sprint 22: Modelo de Planes y Cuotas (Semana 2-3)

- [x] **T-092** Schema de planes y cuotas en BD ✅ v0.26.1
  - Nuevas columnas en `public.tenants`:
    - `plan_type VARCHAR(20) DEFAULT 'demo'` — demo, starter, business, enterprise
    - `document_quota INTEGER DEFAULT 25` — documentos permitidos en el periodo
    - `documents_used INTEGER DEFAULT 0` — documentos emitidos en el periodo actual
    - `plan_starts_at TIMESTAMPTZ` — inicio del periodo
    - `plan_expires_at TIMESTAMPTZ` — fin del periodo (30 días desde activación)
  - Nueva tabla `public.plan_renewals`:
    - `renewal_id UUID PK`, `tenant_id FK`, `plan_type`, `document_quota`
    - `amount NUMERIC(10,2)`, `payment_proof_path VARCHAR(500)` (ruta MinIO)
    - `status VARCHAR(20)` — pending, approved, rejected
    - `approved_by`, `approved_at`, `notes`, `created_at`
  - Enum Java `PlanType` con cuotas por defecto: DEMO(25), STARTER(100), BUSINESS(500), ENTERPRISE(5000)
  - Script SQL en `db/migrations/public/`
  - Test: verificar defaults y constraints

- [x] **T-093** Validación de cuota en emisión de documentos ✅ v0.26.2
  - En cada servicio de creación (Invoice, CreditNote, DebitNote, Waybill, Withholding, PurchaseClearance, RawDocument):
    - Antes de INSERT: verificar `documents_used < document_quota`
    - Si excede → HTTP 402 Payment Required con mensaje "Cuota de documentos agotada. Renueve su plan."
    - Si plan expirado (`plan_expires_at < now()`) → HTTP 402 con "Plan expirado"
  - Incrementar `documents_used` atómicamente: `UPDATE tenants SET documents_used = documents_used + 1 WHERE tenant_id = ? AND documents_used < document_quota`
  - El UPDATE atómico previene race conditions bajo concurrencia
  - No contar documentos en estado REJECTED o FAILED (solo los que avanzan a SIGNED+)
  - Test: emitir documentos hasta agotar cuota, verificar 402
  - Test: concurrencia — 10 requests simultáneos con 1 documento restante → solo 1 éxito

- [x] **T-094** Alertas de cuota por webhook y email ✅ `v0.26.3`
  - Webhook `plan.quota_warning` cuando `documents_used >= 80%` de `document_quota`
  - Webhook `plan.quota_exhausted` cuando se agota la cuota
  - Webhook `plan.expiring` 7 días antes de `plan_expires_at`
  - Email al tenant en cada caso (usando SMTP de Key49 o SMTP propio del tenant)
  - Job `@Scheduled` diario para verificar planes próximos a vencer
  - Test: simular 80% uso, verificar webhook disparado

### Sprint 23: SMTP por Tenant (Semana 3-4)

- [x] **T-095** Configuración SMTP por tenant ✅ v0.26.4
  - Nuevas columnas en `public.tenants`:
    - `smtp_host VARCHAR(255)`, `smtp_port INTEGER`, `smtp_user VARCHAR(255)`
    - `smtp_password_enc BYTEA` (cifrado AES-256-GCM, misma clave maestra)
    - `smtp_from VARCHAR(255)`, `smtp_enabled BOOLEAN DEFAULT false`
  - Script SQL en `db/migrations/public/`
  - Endpoint `PUT /v1/admin/tenants/{id}/smtp` para configurar SMTP
  - Endpoint `POST /v1/admin/tenants/{id}/smtp/test` para enviar email de prueba
  - Test de conexión SMTP antes de guardar (validar host:port accesible)
  - Test: configurar SMTP, enviar email de prueba, verificar recepción

- [x] **T-096** Envío de email con SMTP del tenant ✅ v0.26.5
  - Modificar `EmailService` para resolver el SMTP correcto:
    - Si `tenant.smtp_enabled = true` → usar SMTP del tenant
    - Si `tenant.smtp_enabled = false` → usar SMTP compartido de Key49
  - Crear `SmtpClientFactory` que construya clientes SMTP bajo demanda (con caché LRU por tenant)
  - Descifrar `smtp_password_enc` solo al momento de enviar (no cachear contraseña en claro)
  - Fallback: si SMTP del tenant falla 3 veces → usar SMTP de Key49 + log warning
  - Test: enviar email con SMTP custom, verificar headers From
  - **Extra**: flag `email_notifications_enabled` por tenant para desactivar envío de email (estado `SKIPPED`)

### Sprint 24: Portal de Autoregistro (Semana 4-6)

- [x] **T-097** Autenticación del portal por contraseña (además de API key) ✅ v0.27.0
  - Nueva columna `portal_password_hash VARCHAR(255)` en `public.tenants` (bcrypt)
  - Nueva columna `email VARCHAR(255)` y `email_verified BOOLEAN DEFAULT false`
  - Modificar `PortalAuthFilter` para soportar login por email + contraseña (además del API key existente)
  - Endpoint `POST /portal/login` acepta `{email, password}` o `{apiKey}`
  - Hashear con bcrypt (cost factor 12)
  - Sesión Redis existente se reutiliza (30 min TTL)
  - Test: login con contraseña, verificar sesión creada

- [x] **T-098** Wizard de autoregistro — Paso 1: Datos de empresa ✅ v0.27.1
  - Template Qute: `/portal/register` con formulario multi-paso (Pico CSS + HTMX)
  - Paso 1: RUC, razón social, email, contraseña, confirmar contraseña
  - Validación de que el RUC no esté ya registrado
  - **Si el RUC ya está registrado → BLOQUEAR registro**, no proceder a los siguientes pasos, mostrar alerta sugiriendo recuperar la contraseña (enlace a `/portal/forgot-password` — ver T-101b)
  - Validación client-side (HTMX): verificar RUC con módulo 11, email formato válido
  - Validación server-side: RUC no duplicado, email no duplicado, contraseña mínimo 8 caracteres
  - Al completar paso 1: guardar datos en sesión temporal (Redis, TTL 30 min), no crear tenant aún
  - Test: validación de RUC, detección de duplicados, bloqueo por RUC existente

- [x] **T-099** Wizard de autoregistro — Paso 2: Certificado .p12 ✅ v0.27.2
  - Upload de archivo .p12 (máximo 50 KB, validar extensión y magic bytes)
  - Campo de contraseña del certificado
  - Selector de ambiente: TEST / PRODUCCIÓN (radio buttons)
  - Validación server-side: intentar cargar el .p12 con la contraseña para verificar que es válido
  - Extraer del certificado: emisor, fecha de expiración, serial number
  - Mostrar resumen del certificado al usuario antes de continuar
  - Cifrar con AES-256-GCM y guardar en sesión temporal
  - Test: upload de certificado válido, rechazo de archivo inválido

- [x] **T-100** Wizard de autoregistro — Paso 3: SMTP y webhook (opcional) ✅ v0.27.3
  - Formulario opcional de SMTP: host, puerto, usuario, contraseña, email remitente
  - Botón "Probar conexión SMTP" (HTMX → POST que verifica conexión)
  - Campo opcional de webhook URL (con validación SSRF)
  - Si no configura SMTP → mostrar mensaje "Se usará el servicio de email de Key49"
  - Guardar en sesión temporal
  - Test: configurar SMTP, probar conexión

- [x] **T-101** Wizard de autoregistro — Paso 4: Confirmación y creación ✅ v0.27.4
  - Resumen de toda la configuración (datos empresa, certificado, ambiente, SMTP)
  - Botón "Crear mi cuenta"
  - Al confirmar:
    1. Crear tenant con plan DEMO (25 docs, 30 días) via `TenantAdminService.create()`
    2. Provisioning automático del esquema (T-091)
    3. Guardar certificado cifrado
    4. Configurar SMTP si fue proporcionado
    5. Generar API key y mostrarla UNA SOLA VEZ (con botón copiar)
    6. Enviar email de bienvenida con guía rápida de integración
  - Redirigir al dashboard del portal con mensaje de bienvenida
  - Test E2E: completar wizard completo, verificar tenant activo y API key funcional

- [x] **T-101b** Recuperación de contraseña del portal ✅ v0.27.5
  - Página `/portal/forgot-password` con campo para email
  - Generar token de recuperación (UUID), guardar en Redis con TTL 30 min (`portal:reset:{token}`)
  - Enviar email con enlace `/portal/reset-password?token={token}`
  - Página `/portal/reset-password` con campos: nueva contraseña, confirmar contraseña
  - Validar token en Redis, actualizar `portal_password_hash` en `public.tenants`
  - Invalidar token después de uso (eliminar de Redis)
  - Rate limiting: máximo 3 solicitudes por email por hora
  - Test: flujo completo de recuperación, token expirado, token inválido

### Sprint 25: Panel Admin de Renovaciones (Semana 6-7)

- [x] **T-102** Solicitud de renovación desde el portal del tenant ✅ v0.27.6
  - Nueva página `/portal/plan` que muestra: plan actual, documentos usados/total, fecha expiración
  - Barra de progreso visual de cuota (verde < 50%, amarillo 50-80%, rojo > 80%)
  - Lista de planes disponibles con precios
  - Botón "Renovar plan" → formulario:
    - Seleccionar plan deseado
    - Subir comprobante de pago (imagen JPG/PNG o PDF, máximo 5 MB)
    - Campo de observaciones
  - El comprobante se sube a MinIO: `plan-renewals/{tenant_id}/{renewal_id}.{ext}`
  - Se crea registro en `plan_renewals` con `status = 'pending'`
  - Webhook `plan.renewal_requested` al admin
  - Test: subir comprobante, verificar registro creado

- [ ] **T-103** Panel de administración de renovaciones
  - Endpoint admin: `GET /v1/admin/renewals?status=pending` — lista solicitudes pendientes
  - Endpoint admin: `GET /v1/admin/renewals/{id}` — detalle con link al comprobante en MinIO
  - Endpoint admin: `POST /v1/admin/renewals/{id}/approve` — aprobar renovación:
    - Actualizar `plan_renewals.status = 'approved'`, `approved_by`, `approved_at`
    - Actualizar tenant: `plan_type`, `document_quota`, `documents_used = 0`, nueva `plan_expires_at`
    - Invalidar caché Redis del tenant
    - Webhook `plan.activated` al tenant
    - Email de confirmación al tenant
  - Endpoint admin: `POST /v1/admin/renewals/{id}/reject` — rechazar con motivo
    - Webhook `plan.rejected` al tenant
  - Portal admin (`/portal/admin/renewals`): tabla con filtros, botones aprobar/rechazar
  - Test: flujo completo solicitud → aprobación → cuota actualizada

### Sprint 26: Pulido y Hardening (Semana 7-8)

- [ ] **T-104** Reset mensual de cuota (job programado)
  - Job `@Scheduled` diario que verifica planes con suscripción activa:
    - Si `plan_expires_at <= now()` y plan no es Enterprise → `status = 'expired'`, webhook `plan.expired`
    - Para suscripciones con auto-renovación futura: reset `documents_used = 0`, nueva `plan_expires_at`
  - Mantener historial de periodos en `plan_renewals`
  - Test: simular expiración, verificar cambio de estado

- [ ] **T-105** Email de verificación en autoregistro
  - Al registrarse, enviar email con token de verificación (UUID, expira en 24h)
  - Endpoint `GET /portal/verify?token=...` → marca `email_verified = true`
  - El tenant queda en `status = 'pending'` hasta verificar email
  - Después de verificar → provisioning automático → `status = 'active'`
  - Test: generar token, verificar, confirmar activación

- [ ] **T-106** Rate limiting por plan
  - Ajustar `rate_limit_rpm` según el plan del tenant:
    - DEMO: 10 rpm (solicitudes por minuto)
    - STARTER: 30 rpm
    - BUSINESS: 60 rpm
    - ENTERPRISE: 200 rpm
  - Aplicar automáticamente al cambiar de plan
  - Test: verificar 429 con plan DEMO a más de 10 rpm

- [ ] **T-107** Documentación de planes y autoregistro
  - Actualizar `API.md` con endpoints de renovación y planes
  - Actualizar `DEVELOPER-GUIDE.md` con flujo de autoregistro
  - Crear `docs/PLANS.md` con detalle de planes, precios placeholder, y políticas
  - Actualizar `OPERATIONS.md` con flujo de renovación y panel admin

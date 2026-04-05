# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/),
y este proyecto adhiere a [Semantic Versioning](https://semver.org/lang/es/).

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

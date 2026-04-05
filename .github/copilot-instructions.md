# Key49 — Instrucciones para GitHub Copilot

## Proyecto

Plataforma SaaS multi-tenant de facturación electrónica para Ecuador (SRI). APIs REST para emitir, firmar (XAdES-BES), enviar por SOAP al SRI, generar RIDE (PDF) y notificar por email/webhook.

## Documentación Obligatoria

Antes de generar código, leer los archivos relevantes:

- `docs/SPEC.md` — Normativa SRI, flujo de comprobantes, clave de acceso
- `docs/ARCHITECTURE.md` — ADRs, módulos, colas RabbitMQ, state machine, outbox, Redis, tracing
- `docs/DATABASE.md` — Schema PostgreSQL completo (public + tenant)
- `docs/API.md` — Contrato REST API, catálogo de errores, webhooks
- `docs/CONVENTIONS.md` — Reglas de código, enums SRI, validaciones, timezone
- `docs/TASKS.md` — Roadmap por fases y sprints
- `CLAUDE.md` — Reglas críticas numeradas (1-20)

## Idioma

- **Código fuente** (Java, SQL, config, logs): INGLÉS
- **Documentación** (.md, JavaDoc): ESPAÑOL
- **Conversaciones**: ESPAÑOL
- **Commits**: español, formato convencional `tipo(módulo): descripción`
- **Excepción**: XML builders del SRI usan español según XSD (`infoTributaria`, `fechaEmision`)

## Stack

Java 25 + Quarkus 3.34 | PostgreSQL 16 | RabbitMQ | MinIO | Redis

## Reglas Fundamentales

1. **No Lombok** — usar records Java 25 para DTOs
2. **Reactive** — retornos `Uni<T>` (Mutiny), SOAP con `@Blocking`
3. **Multi-tenancy** — schema-per-tenant, `SET search_path` por request, NO columna `tenant_id` en tablas tenant
4. **Zona horaria** — siempre `LocalDate.now(Key49Constants.EC_ZONE)`, nunca `LocalDate.now()` sin zona
5. **Catálogos SRI** — enums Java en key49-core, NO tablas BD
6. **State machine** — usar `DocumentStatus.canTransitionTo()`, nunca asignar estado directamente
7. **Sin tablas de detalle** — ítems/pagos en `request_payload` (JSON) o `original_xml` + MinIO
8. **Sin secuenciales** — `sequence_number` viene del cliente
9. **Emisión mismo día** — validar `issue_date == hoy` (zona Ecuador)
10. **Unicidad** — UNIQUE `(document_type, establishment, issue_point, sequence_number)`
11. **Idempotencia** — toda operación POST soporta `X-Idempotency-Key`
12. **Sin migraciones automáticas** — NO Flyway/Liquibase al arrancar

## Estructura de Paquetes

```
auracore.key49.{module}.{layer}
Ejemplo: auracore.key49.api.resource.InvoiceResource
         auracore.key49.core.model.Document
         auracore.key49.xml.builder.InvoiceXmlBuilder
```

## Módulos Maven

```
key49-api     → REST + portal web (Qute + HTMX + Pico CSS)
key49-core    → Entidades, servicios, repositorios, enums SRI
key49-xml     → XML builders, XSD, clave de acceso
key49-signer  → Firma XAdES-BES, certificados .p12
key49-sri     → Cliente SOAP (Recepción + Autorización)
key49-queue   → Consumers/Producers RabbitMQ, reintentos, outbox poller
key49-ride    → RIDE (PDF)
key49-notify  → Email, webhooks
key49-storage → MinIO/S3
key49-admin   → Métricas, health checks
```

## Pipeline de Documentos

```
CREATED → SIGNED → SENT → RECEIVED → AUTHORIZED → NOTIFIED
                                          ↓
Error de negocio → REJECTED (terminal)
Infraestructura  → RETRY → SENT (o FAILED si agota reintentos)
Post-autorización → VOIDED (anulación local)
```

## BD — Convenciones

- Tablas: inglés, plural, snake_case
- PKs: `{table_singular}_id` (UUID)
- Montos: `NUMERIC(14,2)`
- Timestamps: `TIMESTAMP WITH TIME ZONE`

## Validaciones en Frontera

- RUC: 13 dígitos + módulo 11
- Cédula: 10 dígitos + módulo 10
- `establishment`: 3 dígitos, `issue_point`: 3 dígitos, `sequence_number`: 9 dígitos
- Validar `tax.code`, `payment_method`, `recipient.id_type` contra enums SRI

## Git

- Semver `vMAJOR.MINOR.PATCH`
- Ramas: `feature/T-XXX-desc`, `fix/T-XXX-desc`
- Tests obligatorios antes de merge (`mvn verify`)

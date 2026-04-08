# CLAUDE.md — Instrucciones para Agentes de IA (Claude, Copilot, etc.)

> **Nota**: Este archivo es leído por Claude (CLI/API) y también por GitHub Copilot como adjunto.
> Las instrucciones específicas de Copilot viven en `.github/copilot-instructions.md` (globales)
> y `.github/instructions/*.instructions.md` (por tipo de archivo: Java, SQL, config, portal, docs).

## Proyecto: Key49 — Facturación Electrónica SRI Ecuador

### ¿Qué es este proyecto?

Plataforma SaaS multi-tenant que expone APIs REST para emitir comprobantes electrónicos al SRI de Ecuador. El sistema genera XML, firma con XAdES-BES (certificado .p12), envía por SOAP al SRI, recibe autorización, genera el RIDE (PDF) y envía por email al cliente.

### Stack

Java 25 + Quarkus 3.34 | PostgreSQL 16 | RabbitMQ | MinIO | Redis

### Documentos de Referencia

Lee SIEMPRE estos archivos antes de generar código:

- `docs/SPEC.md` — Especificación del producto, normativa SRI, flujos
- `docs/ARCHITECTURE.md` — Decisiones técnicas, estructura de módulos, colas RabbitMQ
- `docs/DATABASE.md` — Schema PostgreSQL completo con todas las tablas
- `docs/API.md` — Contrato REST API con ejemplos de request/response
- `docs/CONVENTIONS.md` — Convenciones de código, nombrado, testing
- `docs/TASKS.md` — Roadmap y plan de desarrollo por fases y sprints

---

## Reglas de Idioma

1. **Código fuente**: SIEMPRE en inglés (variables, métodos, clases, enums, constantes, mensajes de log).
2. **Documentación** (`.md`, JavaDoc de alto nivel): en español.
3. **Conversaciones** con desarrolladores: en español.
4. **Base de datos**: TODO en inglés con snake_case. Tablas en plural (`documents`, `webhook_deliveries`). Columnas en inglés (`issue_date`, `access_key`, `status`, `created_at`). Claves primarias con formato `{table_singular}_id`.
5. **XML para SRI**: excepción — en el código Java que construye XML para el SRI, se usan los nombres de elementos en español tal como los define el XSD (`infoTributaria`, `razonSocial`, `fechaEmision`, etc.).
6. **Commits y PRs**: mensaje en español, con formato convencional (`tipo(módulo): descripción`).

---

## Reglas Críticas

1. **Multi-tenancy**: Schema-per-tenant. Usar `SET search_path TO 'tenant_{uuid_short}', public;` en cada request. Las tablas de negocio NO tienen columna `tenant_id`.
2. **No Lombok**: Usar records de Java 25 para DTOs. No agregar Lombok al POM.
3. **Reactive**: Retornos de servicio con `Uni<T>` (Mutiny). Operaciones SOAP son `@Blocking`.
4. **Reintentos**: Solo errores de infraestructura se reintentan. Errores de negocio del SRI van directo a FAILED.
5. **Firma XAdES-BES**: Apache Santuario + BouncyCastle. Esquema XAdES 1.3.2. Enveloped. Nodo padre "comprobante".
6. **Clave de acceso**: 49 dígitos con módulo 11 como dígito verificador. Estructura en SPEC.md.
7. **Tests**: Usar `@QuarkusTest` con DevServices. Cobertura mínima 80%.
8. **Idempotencia**: Toda operación POST soporta `X-Idempotency-Key` header.
9. **Versionado Git**: Semantic Versioning `vMAJOR.MINOR.PATCH`. Tag en Git al finalizar cada tarea funcional. Ver sección Git Workflow.
10. **Roadmap**: Seguir el plan de `TASKS.md`. Al completar cada tarea: tests → commit → tag.
11. **Sin migraciones automáticas**: La aplicación NO ejecuta Flyway/Liquibase al arrancar. Todos los scripts SQL se ejecutan manualmente por el DBA.
12. **Creación de tenants**: Solo INSERT en `public.tenants` (con campo `schema_name`). La creación del esquema PostgreSQL y sus tablas es manual, NO la hace la aplicación.
13. **Sin gestión de secuenciales**: Key49 NO gestiona secuenciales. El `sequence_number` lo proporciona el cliente en su request.
14. **Sin tablas de detalle**: Los ítems y pagos NO se almacenan en tablas separadas. Se preservan en `request_payload` (JSON) o `original_xml` (XML raw) y en los XML almacenados en MinIO.
15. **Emisión mismo día**: La `issue_date` debe ser la fecha del día actual. Key49 valida esto antes de procesar.
16. **Zona horaria Ecuador**: Toda lógica de "fecha actual" usa `America/Guayaquil` (UTC-5). Configurar `ZoneId.of("America/Guayaquil")` como constante. NUNCA usar `LocalDate.now()` sin zona — siempre `LocalDate.now(EC_ZONE)`. Variable de entorno `KEY49_TIMEZONE=America/Guayaquil`.
17. **Catálogos SRI como enums Java**: Los catálogos del SRI (tipos de impuesto, formas de pago, tipos de identificación) se modelan como **enums Java** en el paquete `core`, NO como tablas en BD. Son datos estables que cambian solo con actualizaciones de ficha técnica del SRI (requieren redeploy de todas formas).
18. **Unicidad de documento**: Constraint UNIQUE sobre `(document_type, establishment, issue_point, sequence_number)` en la tabla `documents`. Evita duplicación ante el SRI.
19. **Estado VOIDED**: Los documentos autorizados pueden marcarse como VOIDED localmente (`POST /invoices/:id/void`). Key49 NO anula en el SRI — eso lo hace el contribuyente en el portal del SRI. Key49 solo registra la anulación local.
20. **Máquina de estados**: Las transiciones de estado de un documento son finitas y validadas. Ver state machine en ARCHITECTURE.md. No se permite transición arbitraria.

### Estructura del Proyecto

**Módulo único Maven** (packaging `jar`). La separación lógica se logra por paquetes Java:

```
auracore.key49
├── api        → REST endpoints, filtros, DTOs, portal web (Qute + HTMX + Pico CSS)
├── core       → Entidades, servicios, repositorios, enums SRI
├── xml        → Generación XML, validación XSD, clave de acceso
├── signer     → Firma XAdES-BES, gestión certificados .p12
├── sri        → Cliente SOAP (Recepción + Autorización)
├── queue      → Consumers/Producers RabbitMQ, reintentos
├── ride       → Generación PDF (RIDE)
├── notify     → Email, webhooks
├── storage    → MinIO/S3
└── admin      → Métricas, health checks
```

### Flujo de un comprobante en las colas

```
API Request → Validar + Persistir Document(CREATED)
  → cola:sign → Generar XML + Clave Acceso + Firma → Document(SIGNED)
  → cola:send → SOAP Recepción → Document(RECEIVED)
  → cola:authorize → SOAP Autorización → Document(AUTHORIZED)
  → cola:notify → RIDE + Email + Webhook → Document(NOTIFIED)

Estados de error:  REJECTED (SRI rechazó), FAILED (reintentos agotados)
Estado intermedio: RETRY (reintentando, con backoff exponencial)
Estado posterior:  VOIDED (anulado localmente, post-autorización)
```

### Errores del SRI que NO se reintentan

Códigos: 35 (ya registrado), 45 (fecha fuera de rango), 52 (estructura inválida), 65 (fecha futura).

### Errores que SÍ se reintentan

Timeouts, conexión rechazada, HTTP 500 del SRI, código 43 (clave duplicada → regenerar clave).

### Ambiente de Pruebas SRI

- Recepción: `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl`
- Autorización: `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl`

---

## Git Workflow

### Versionado Semántico (3 niveles)

Formato: `vMAJOR.MINOR.PATCH`

- **MAJOR** (1.x.x): cambios incompatibles en la API pública. La versión 1.0.0 se crea al salir a producción (Fase 2).
- **MINOR** (x.1.x): nueva funcionalidad backward-compatible (ej: completar un Sprint o bloque de tareas).
- **PATCH** (x.x.1): correcciones de bugs, ajustes menores.

### Ramas

| Rama                        | Propósito                                |
| --------------------------- | ---------------------------------------- |
| `main`                      | Código estable, solo via merge de PRs    |
| `develop`                   | Integración de features antes de release |
| `feature/T-XXX-descripcion` | Una tarea del TASKS.md                   |
| `fix/T-XXX-descripcion`     | Corrección de bug                        |
| `release/vX.Y.Z`            | Preparación de release                   |
| `hotfix/vX.Y.Z-descripcion` | Fix urgente en producción                |

### Flujo por tarea

1. Crear rama `feature/T-XXX-descripcion` desde `develop`
2. Implementar la tarea con commits convencionales
3. Ejecutar tests (`mvn verify`)
4. Crear PR hacia `develop`
5. Al hacer merge, crear tag si corresponde

### Tags por fase

| Hito                                      | Tag                     |
| ----------------------------------------- | ----------------------- |
| Sprint 1 completado                       | `v0.1.0`                |
| Sprint 2 completado                       | `v0.2.0`                |
| Sprint 3 completado                       | `v0.3.0`                |
| Sprint 4 / MVP completado                 | `v0.4.0`                |
| Fase 2 / Producción                       | `v1.0.0`                |
| Cada tipo de documento adicional (Fase 3) | `v1.1.0`, `v1.2.0`, ... |

---

## Buenas Prácticas Adicionales

1. **Fail fast, recover gracefully**: validar inputs al inicio. Manejar errores del SRI con reintentos controlados.
2. **Immutability first**: usar records, listas inmutables (`List.of()`), evitar setters.
3. **Tenant isolation always**: el aislamiento se garantiza por esquema de PostgreSQL (`SET search_path`). Nunca acceder a tablas de un tenant sin configurar el `search_path` correcto.
4. **Idempotency by default**: cada operación de escritura debe ser idempotente.
5. **Observable**: todo evento relevante debe generar un log estructurado y una métrica.
6. **No over-engineering**: implementar solo lo necesario para la tarea actual. No anticipar requisitos futuros.
7. **Seguridad desde el diseño**: validar inputs en frontera del sistema, queries parametrizadas, secretos en variables de entorno.
8. **Backward compatibility**: nunca romper el contrato de la API REST pública sin incrementar MAJOR version.
9. **Dependency pinning**: fijar versiones de dependencias en el POM. Actualizar de forma controlada.
10. **Changelog**: mantener un archivo `CHANGELOG.md` actualizado con cada release.

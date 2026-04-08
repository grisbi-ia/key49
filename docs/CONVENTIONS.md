# Convenciones de Código — Key49

## Para Agentes de IA (Copilot, Claude, etc.)

Este archivo define las reglas que todo agente de IA debe seguir al generar código para este proyecto. Leerlo ANTES de generar cualquier código.

---

## Reglas de Idioma

| Contexto                                                      | Idioma                     | Ejemplo                                            |
| ------------------------------------------------------------- | -------------------------- | -------------------------------------------------- |
| Código fuente (variables, métodos, clases, enums, constantes) | Inglés                     | `generateAccessKey()`, `DocumentStatus.AUTHORIZED` |
| Mensajes de log                                               | Inglés                     | `"Document sent to SRI"`                           |
| Documentación `.md`, JavaDoc de alto nivel                    | Español                    | `"Genera la clave de acceso de 49 dígitos"`        |
| Conversaciones con desarrolladores                            | Español                    | —                                                  |
| Commits y PRs                                                 | Español (convencional)     | `feat(xml): implementar builder de factura`        |
| Nombres de tablas BD                                          | Inglés, plural, snake_case | `documents`, `webhook_deliveries`                  |
| Columnas BD                                                   | Inglés, snake_case         | `issue_date`, `legal_name`, `access_key`           |
| Claves primarias BD                                           | `{table_singular}_id`      | `tenant_id`, `document_id`                         |
| XML para SRI (builders)                                       | Español (XSD del SRI)      | `infoTributaria`, `razonSocial`, `fechaEmision`    |

### Regla de oro para código

**Todo el código fuente (Java, SQL, configuración) va en inglés**. La única excepción es el código que construye XML para el SRI, donde se usan los nombres de elementos/atributos en español tal como los define el XSD del SRI (`infoTributaria`, `razonSocial`, `fechaEmision`, etc.).

---

## Convenciones de Base de Datos

### Nombrado

| Elemento          | Convención                       | Ejemplo                    |
| ----------------- | -------------------------------- | -------------------------- |
| Tabla             | Inglés, plural, snake_case       | `documents`                |
| Columna           | Inglés, snake_case               | `issue_date`, `access_key` |
| Clave primaria    | `{table_singular}_id`            | `document_id`              |
| Clave foránea     | `{referenced_table_singular}_id` | `tenant_id`                |
| Índice            | `idx_{table}_{column}`           | `idx_documents_status`     |
| Constraint UNIQUE | `uq_{table}_{column}`            | `uq_tenants_ruc`           |
| Constraint CHECK  | `chk_{table}_{condition}`        | `chk_documents_status`     |

### Tipos preferidos

| Uso             | Tipo PostgreSQL                  |
| --------------- | -------------------------------- |
| Identificadores | `UUID DEFAULT gen_random_uuid()` |
| Texto corto     | `VARCHAR(n)`                     |
| Texto largo     | `TEXT`                           |
| Montos          | `NUMERIC(14,2)`                  |
| Cantidades      | `NUMERIC(14,6)`                  |
| Booleanos       | `BOOLEAN NOT NULL DEFAULT false` |
| Fechas          | `DATE`                           |
| Timestamps      | `TIMESTAMP WITH TIME ZONE`       |
| JSON flexible   | `JSONB`                          |
| Binario         | `BYTEA`                          |

### Columnas estándar en todas las tablas

**Tablas del esquema público** (`tenants`, `api_keys`):

```sql
{table_singular}_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),  -- if applicable
```

**Tablas del esquema del tenant** (aisladas por schema, NO tienen `tenant_id`):

```sql
{table_singular}_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),  -- if applicable
```

> **Nota**: El aislamiento multi-tenant se garantiza por esquema de PostgreSQL (`SET search_path TO 'tenant_{uuid_short}', public;`), no por columna discriminadora.

---

## Stack Técnico

- **Java 25 LTS** — usar records, sealed interfaces, pattern matching, text blocks donde aplique
- **Quarkus 3.x** — preferir extensiones Quarkus sobre librerías genéricas
- **Maven** — single-module project (packaging `jar`)
- **PostgreSQL 16** — via Hibernate Reactive + Panache
- **RabbitMQ** — via SmallRye Reactive Messaging
- **Redis** — via Quarkus Redis
- **MinIO** — via AWS S3 SDK (compatible)

## Estructura de Paquetes

```
auracore.key49.{module}.{layer}

Ejemplo:
auracore.key49.api.resource.InvoiceResource
auracore.key49.core.model.Document
auracore.key49.core.service.DocumentService
auracore.key49.core.repository.DocumentRepository
auracore.key49.xml.builder.InvoiceXmlBuilder
auracore.key49.signer.XAdESBESSigner
auracore.key49.sri.client.SriReceptionClient
auracore.key49.queue.consumer.SignConsumer
auracore.key49.ride.generator.InvoiceRideGenerator
auracore.key49.notify.email.EmailService
auracore.key49.storage.ObjectStorageService
```

## Reglas de Código Java

### General

- Usar `var` para variables locales donde el tipo es obvio
- Usar `Optional` para retornos que pueden ser vacíos, NUNCA para parámetros
- Usar `record` para DTOs, request/response objects, y value objects inmutables
- Usar `sealed interface` para jerarquías cerradas (ej: tipos de documento)
- NO usar Lombok — Java 25 records reemplazan la mayoría de casos
- Logs con `@Inject Logger log` (Quarkus) o `Log.info()` (JBoss Logging)
- Nombres en inglés para código, comentarios en español son aceptables

### DTOs y Request/Response

```java
// CORRECTO: usar records para DTOs
public record CreateInvoiceRequest(
    String establishment,
    String issuePoint,
    LocalDate issueDate,
    RecipientDto recipient,
    List<ItemDto> items,
    List<PaymentDto> payments,
    @Nullable Map<String, String> additionalInfo
) {}

public record InvoiceResponse(
    UUID id,
    String documentType,
    String accessKey,
    DocumentStatus status,
    BigDecimal totalAmount,
    Instant createdAt
) {}
```

### Entidades JPA

```java
// Usar Panache Entity o PanacheRepository
// Campos Java en inglés, columnas BD en inglés
// Tablas del esquema tenant NO tienen columna tenant_id
@Entity
@Table(name = "documents")
public class Document extends PanacheEntityBase {
    @Id
    @GeneratedValue
    @Column(name = "document_id")
    public UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public DocumentStatus status;

    @Column(name = "access_key", length = 49, unique = true)
    public String accessKey;

    @Version
    @Column(name = "version")
    public int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    // ... fields per DATABASE.md
}
```

### Servicios de Negocio

```java
@ApplicationScoped
public class DocumentService {

    @Inject
    DocumentRepository documentRepo;

    @Inject
    @Channel("doc-sign")
    Emitter<DocumentMessage> signEmitter;

    @Transactional
    public Uni<Document> createInvoice(UUID tenantId, CreateInvoiceRequest request) {
        // 1. Validar request (sequence_number viene del cliente)
        // 2. Validar issue_date = hoy
        // 3. Persistir documento (solo resumen en documents, sin tablas de detalle)
        // 4. Publicar a cola de firma
        // 5. Retornar documento creado
    }
}
```

### Endpoints REST (JAX-RS)

```java
@Path("/v1/invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated  // requiere API key
public class InvoiceResource {

    @Inject
    DocumentService documentService;

    @Inject
    TenantContext tenantContext; // propagado por el auth filter

    @POST
    @ResponseStatus(202)
    public Uni<Response> createInvoice(
        @Valid CreateInvoiceRequest request,
        @HeaderParam("X-Idempotency-Key") String idempotencyKey
    ) {
        return documentService
            .createInvoice(tenantContext.getTenantId(), request)
            .map(doc -> Response.accepted(InvoiceResponse.from(doc)).build());
    }

    @GET
    @Path("/{id}")
    public Uni<InvoiceResponse> getInvoice(@PathParam("id") UUID id) {
        return documentService.findById(tenantContext.getTenantId(), id)
            .onItem().ifNull().failWith(NotFoundException::new)
            .map(InvoiceResponse::from);
    }
}
```

### Consumidores de Cola

```java
@ApplicationScoped
public class SendConsumer {

    @Inject
    SriReceptionClient sriClient;

    @Incoming("doc-send")
    @Blocking  // operación SOAP es bloqueante
    @Retry(maxRetries = 0)  // reintentos manejados manualmente
    public CompletionStage<Void> process(DocumentMessage msg) {
        // 1. Cargar documento de BD
        // 2. Enviar al SRI via SOAP
        // 3. Parsear respuesta
        // 4. Actualizar estado
        // 5. Publicar siguiente cola o retry
    }
}
```

---

## Zona Horaria

Toda lógica de "fecha actual" usa `America/Guayaquil` (UTC-5). NUNCA usar `LocalDate.now()` sin zona.

```java
// CONSTANTE global en el paquete core
public final class Key49Constants {
    public static final ZoneId EC_ZONE = ZoneId.of("America/Guayaquil");

    private Key49Constants() {}
}

// CORRECTO
LocalDate today = LocalDate.now(Key49Constants.EC_ZONE);
Instant now = Instant.now(); // Instant siempre es UTC, OK

// INCORRECTO — nunca hacer esto
LocalDate today = LocalDate.now(); // usa zona del servidor, puede ser UTC
LocalDateTime.now();                // ambiguo
```

La zona se configura via variable de entorno `KEY49_TIMEZONE=America/Guayaquil`.

---

## Catálogos SRI como Enums Java

Los catálogos del SRI se modelan como **enums Java** en el paquete `core`, NO como tablas en base de datos. Son datos estables que cambian solo con actualizaciones de ficha técnica del SRI (que requieren redeploy de todas formas).

### Enums a implementar

| Enum                 | Descripción            | Ejemplo de valores                                                    |
| -------------------- | ---------------------- | --------------------------------------------------------------------- |
| `DocumentType`       | Tipo de comprobante    | `INVOICE("01")`, `CREDIT_NOTE("04")`, `WITHHOLDING("07")`             |
| `DocumentStatus`     | Estado en el pipeline  | `CREATED`, `SIGNED`, `AUTHORIZED`, `VOIDED`                           |
| `TaxType`            | Código de impuesto     | `IVA("2")`, `ICE("3")`, `IRBPNR("5")`                                 |
| `VatRate`            | Porcentaje IVA         | `ZERO("0", 0)`, `TWELVE("2", 12)`, `FIFTEEN("4", 15)`                 |
| `PaymentMethod`      | Forma de pago SRI      | `CASH("01")`, `WIRE_TRANSFER("20")`, `CREDIT_CARD("19")`              |
| `IdentificationType` | Tipo de identificación | `RUC("04")`, `CEDULA("05")`, `PASSPORT("06")`, `FINAL_CONSUMER("07")` |
| `SriEnvironment`     | Ambiente SRI           | `TEST("1")`, `PRODUCTION("2")`                                        |

### Patrón para enums con código SRI

```java
public enum IdentificationType {
    RUC("04", 13),
    CEDULA("05", 10),
    PASSPORT("06", -1),      // longitud variable
    FINAL_CONSUMER("07", 13);

    private final String sriCode;
    private final int length; // -1 = variable

    IdentificationType(String sriCode, int length) {
        this.sriCode = sriCode;
        this.length = length;
    }

    public String sriCode() { return sriCode; }
    public int length() { return length; }

    public static IdentificationType fromSriCode(String code) {
        return Arrays.stream(values())
            .filter(t -> t.sriCode.equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown SRI code: " + code));
    }
}
```

---

## Validaciones de Formato en Frontera

Validar en la capa API (resource/filter) antes de que el request llegue a servicios de negocio.

### Reglas de validación de campos SRI

| Campo               | Formato       | Validación                                   |
| ------------------- | ------------- | -------------------------------------------- |
| `establishment`     | `^\d{3}$`     | Exactamente 3 dígitos numéricos              |
| `issue_point`       | `^\d{3}$`     | Exactamente 3 dígitos numéricos              |
| `sequence_number`   | `^\d{9}$`     | Exactamente 9 dígitos numéricos              |
| RUC                 | `^\d{13}$`    | 13 dígitos + dígito verificador módulo 11    |
| Cédula              | `^\d{10}$`    | 10 dígitos + dígito verificador módulo 10    |
| Pasaporte           | `.{3,20}`     | 3 a 20 caracteres alfanuméricos              |
| `issue_date`        | `yyyy-MM-dd`  | Debe ser la fecha actual (America/Guayaquil) |
| `tax.code`          | `1-5`         | Debe existir en enum `TaxType`               |
| `tax.rate_code`     | Catálogo SRI  | Debe existir en enum `VatRate`               |
| `payment_method`    | `01-20`       | Debe existir en enum `PaymentMethod`         |
| `recipient.id_type` | `04,05,06,07` | Debe existir en enum `IdentificationType`    |

### Algoritmo de validación de RUC (módulo 11)

```java
// Posiciones: coeficientes [4,3,2,7,6,5,4,3,2] aplicados a los primeros 9 dígitos
// Sumar productos, módulo 11, restar de 11
// Si resultado == 11 → dígito = 0; si resultado == 10 → RUC inválido
// Comparar con dígito en posición 9 (0-indexed)
```

### Algoritmo de validación de Cédula (módulo 10)

```java
// Par/impar: dígitos en posición impar (0-indexed) × 2, si > 9 restar 9
// Sumar todos, módulo 10, restar de 10
// Si resultado == 10 → dígito = 0
// Comparar con último dígito
```

---

## Transición de Estado (State Machine)

Toda transición de estado de un documento debe pasar por el método `canTransitionTo()` del enum `DocumentStatus`. Ver ARCHITECTURE.md para la tabla completa de transiciones válidas.

```java
// CORRECTO: validar transición antes de cambiar estado
public void transitionTo(DocumentStatus target) {
    if (!this.status.canTransitionTo(target)) {
        throw new InvalidStateTransitionException(this.status, target);
    }
    this.status = target;
    this.updatedAt = Instant.now();
}

// INCORRECTO: cambiar estado directamente
document.status = DocumentStatus.AUTHORIZED; // NO — no valida transición
```

### Manejo de Errores

```java
// Excepciones de dominio
public sealed class Key49Exception extends RuntimeException
    permits ValidationException, SriException, CertificateException {

    private final String errorCode;
    // ...
}

// Exception Mapper para JAX-RS
@Provider
public class Key49ExceptionMapper implements ExceptionMapper<Key49Exception> {
    @Override
    public Response toResponse(Key49Exception e) {
        return Response.status(e.getHttpStatus())
            .entity(new ErrorResponse(e.getErrorCode(), e.getMessage()))
            .build();
    }
}
```

### Testing

```java
@QuarkusTest
@TestProfile(TestProfile.class)
class InvoiceResourceTest {

    @Test
    void shouldCreateInvoice() {
        given()
            .header("Authorization", "Bearer " + TEST_API_KEY)
            .header("X-Idempotency-Key", UUID.randomUUID().toString())
            .body(validInvoiceRequest())
            .contentType(ContentType.JSON)
        .when()
            .post("/v1/invoices")
        .then()
            .statusCode(202)
            .body("data.status", is("SENT"))
            .body("data.access_key", hasLength(49));
    }
}
```

## Reglas de Nombrado

| Elemento      | Convención                       | Ejemplo                      |
| ------------- | -------------------------------- | ---------------------------- |
| Clase         | PascalCase (inglés)              | `InvoiceXmlBuilder`          |
| Método        | camelCase (inglés)               | `generateAccessKey()`        |
| Variable      | camelCase (inglés)               | `accessKey`                  |
| Constante     | UPPER_SNAKE (inglés)             | `MAX_RETRIES`                |
| Tabla BD      | snake_case, inglés, plural       | `documents`                  |
| Columna BD    | snake_case, inglés               | `issue_date`                 |
| PK            | `{table_singular}_id`            | `document_id`                |
| FK            | `{referenced_table_singular}_id` | `tenant_id`                  |
| Endpoint REST | kebab-case (inglés)              | `/invoices`                  |
| Exchange RMQ  | dot.notation                     | `key49.documents`            |
| Queue RMQ     | dot.notation                     | `key49.sign`                 |
| Package       | lowercase (inglés)               | `auracore.key49.xml.builder` |

## Principios de Diseño

1. **Fail fast, recover gracefully**: validar inputs al inicio, manejar errores del SRI con reintentos
2. **Immutability first**: usar records, listas inmutables, evitar setters
3. **Tenant isolation always**: nunca olvidar el tenant_id en queries y operaciones
4. **Idempotency by default**: cada operación de escritura debe ser idempotente
5. **Observable**: todo evento relevante debe generar un log estructurado y una métrica
6. **No over-engineering**: implementar solo lo necesario para la tarea actual. No anticipar requisitos futuros
7. **Security by design**: validar inputs en frontera del sistema, queries parametrizadas, secretos en variables de entorno
8. **Backward compatibility**: nunca romper el contrato de la API REST pública sin incrementar MAJOR version

## Logs Estructurados

```java
// CORRECTO: log estructurado con contexto
Log.infof("Document sent to SRI | tenant=%s clave=%s status=%s duration=%dms",
    tenantId, claveAcceso, status, durationMs);

// INCORRECTO: log sin contexto
Log.info("Documento enviado");
```

## Formato de Commits

```
tipo(módulo): descripción breve en español

Tipos: feat, fix, refactor, test, docs, chore
Módulo: api, core, xml, signer, sri, queue, ride, notify, storage, admin

Ejemplo:
feat(xml): implementar builder de factura v2.1.0
fix(sri): manejar timeout en recepción de comprobantes
test(signer): agregar test de firma XAdES-BES con certificado expirado
```

---

## Git Workflow

### Versionado Semántico (3 niveles)

Formato: `vMAJOR.MINOR.PATCH`

- **MAJOR** (1.x.x): cambios incompatibles en la API pública
- **MINOR** (x.1.x): nueva funcionalidad backward-compatible (Sprint o bloque de tareas)
- **PATCH** (x.x.1): correcciones de bugs, ajustes menores

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
2. Implementar la tarea con commits convencionales (en español)
3. Ejecutar tests (`mvn verify`) — **obligatorio antes de merge**
4. Crear PR hacia `develop`
5. Al hacer merge, crear tag semántico si corresponde

### Tags por fase

| Hito                                      | Tag                     |
| ----------------------------------------- | ----------------------- |
| Sprint 1 completado                       | `v0.1.0`                |
| Sprint 2 completado                       | `v0.2.0`                |
| Sprint 3 completado                       | `v0.3.0`                |
| Sprint 4 / MVP completado                 | `v0.4.0`                |
| Fase 2 / Producción                       | `v1.0.0`                |
| Cada tipo de documento adicional (Fase 3) | `v1.1.0`, `v1.2.0`, ... |

### Reglas de Git

- **Nunca** push directo a `main` — siempre via PR
- **Nunca** usar `--force` push salvo situaciones críticas y con aprobación
- Todo tag debe corresponder a un estado donde `mvn verify` pasa exitosamente
- El tag se crea **después** de que el merge a `develop` (o `main`) sea exitoso

---

## Testing

### Niveles de test

| Nivel       | Herramienta                  | Alcance                   |
| ----------- | ---------------------------- | ------------------------- |
| Unitario    | JUnit 5 + Mockito            | Lógica de negocio aislada |
| Integración | `@QuarkusTest` + DevServices | Endpoints, repos, colas   |
| End-to-end  | `@QuarkusIntegrationTest`    | Flujo completo con SRI    |

### Reglas de testing

1. **Cobertura mínima**: 80%
2. **DevServices**: usar PostgreSQL, RabbitMQ y Redis en containers (no mocks)
3. **Datos de test**: cada test crea y limpia sus propios datos
4. **Nombres descriptivos**: `shouldRejectInvoiceWithExpiredCertificate()`
5. **Al finalizar cada tarea del TASKS.md**: ejecutar `mvn verify` y confirmar que todo pasa

---

## Archivos de Referencia para Contexto de IA

Cuando trabajes con un agente de IA, incluir estos archivos como contexto:

1. `docs/SPEC.md` — Siempre. Es la fuente de verdad del producto.
2. `docs/ARCHITECTURE.md` — Para decisiones técnicas, estructura de paquetes, configuración.
3. `docs/DATABASE.md` — Cuando trabajes con entidades, repositorios, o migraciones.
4. `docs/API.md` — Cuando trabajes con endpoints REST, DTOs, o webhooks.
5. `docs/TASKS.md` — Para entender el alcance y prioridad de cada tarea.
6. `docs/CONVENTIONS.md` — Siempre. Define cómo debe verse el código.
7. `CLAUDE.md` — Siempre. Define las instrucciones y reglas para agentes de IA.

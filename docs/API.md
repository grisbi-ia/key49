# API REST — Key49

## Base URL

```
Desarrollo: http://localhost:8080/v1
Producción: https://api.key49.ec/v1
Pruebas:    https://sandbox.key49.ec/v1
```

## Autenticación

Todas las peticiones a `/v1/*` requieren un API Key en el header `Authorization`:

```
Authorization: Bearer fec_live_xxxxxxxxxxxxxxxxxxxx
```

Los API keys tienen prefijo `fec_live_` (producción) o `fec_test_` (pruebas).

**Ejemplo curl:**

```bash
curl -s http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Paths públicos** (no requieren autenticación): `/q/*`, `/portal/login`, `/openapi`, `/swagger-ui`.

## Rate Limiting

- Default: 100 requests/minuto por API key (configurable por tenant)
- Headers de respuesta: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
- Al exceder: HTTP 429 con header `Retry-After` y error code `RATE_LIMIT_EXCEEDED`

## Idempotencia

Todas las operaciones de creación soportan idempotencia via header:

```
X-Idempotency-Key: unique-string-from-client
```

Si se envía el mismo key, se retorna el resultado original sin reprocesar. Si se envía el mismo key con un body distinto, se retorna HTTP 409 `IDEMPOTENCY_CONFLICT`.

## Headers de Respuesta Comunes

Todas las respuestas incluyen los siguientes headers:

```
X-Request-Id: req_abc123              # ID único de Key49 para trazabilidad
X-Trace-Id: 4bf92f3577b347a8...       # OpenTelemetry trace ID (si habilitado)
X-RateLimit-Limit: 100               # Límite de requests por minuto
X-RateLimit-Remaining: 95            # Requests restantes en la ventana
X-RateLimit-Reset: 1712234567        # Timestamp UNIX cuando se renueva la ventana
Content-Type: application/json
```

El `X-Request-Id` se incluye también en el body dentro de `meta.request_id`.

---

## Formato de Respuestas

### Respuesta exitosa

```json
{
  "data": { ... },
  "meta": {
    "request_id": "req_abc123",
    "timestamp": "2026-04-04T10:30:00Z"
  }
}
```

### Respuesta con lista (paginada)

```json
{
  "data": [ ... ],
  "meta": {
    "total": 150,
    "page": 1,
    "per_page": 20,
    "total_pages": 8
  }
}
```

### Respuesta de error

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "El campo receptor.identificacion es requerido",
    "details": [
      {
        "field": "receptor.identificacion",
        "message": "No puede estar vacío",
        "code": "REQUIRED"
      }
    ]
  },
  "meta": {
    "request_id": "req_abc123"
  }
}
```

---

## Endpoints

### 1. Facturas (Invoices)

#### POST /v1/invoices

Crear y enviar una factura electrónica al SRI.

> **Nota**: Los campos `establishment`, `issue_point` y `sequence_number` son responsabilidad del cliente. Key49 no gestiona secuenciales. La `issue_date` debe ser la fecha del día actual (emisión en tiempo real).
>
> **Almacenamiento**: Key49 persiste los datos resumen del documento (receptor, totales, estado) en la tabla `documents`. Los ítems y formas de pago NO se almacenan en tablas separadas — se preservan en el `request_payload` original y en los XML almacenados en MinIO.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/invoices \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: inv-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "issue_date": "'$(date +%Y-%m-%d)'",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "address": "Av. Principal 123, Quito",
      "email": "contabilidad@cliente.com",
      "phone": "0991234567"
    },
    "items": [
      {
        "main_code": "PROD-001",
        "auxiliary_code": "7861234567890",
        "description": "Servicio de hosting mensual",
        "unit_of_measure": "UNIDAD",
        "quantity": 1,
        "unit_price": 50.0,
        "discount": 0.0,
        "taxes": [
          {
            "code": "2",
            "rate_code": "4",
            "rate": 15.0
          }
        ]
      }
    ],
    "payments": [
      {
        "payment_method": "20",
        "total": 57.5,
        "term": 0,
        "time_unit": "days"
      }
    ],
    "additional_info": {
      "Dirección": "Av. Principal 123",
      "Email": "contabilidad@cliente.com"
    }
  }' | jq .
```

**Request:**

```json
{
  "establishment": "001",
  "issue_point": "001",
  "sequence_number": "000000042",
  "issue_date": "2026-04-04",
  "recipient": {
    "id_type": "04",
    "id": "1790012345001",
    "name": "Empresa Cliente S.A.",
    "address": "Av. Principal 123, Quito",
    "email": "contabilidad@cliente.com",
    "phone": "0991234567"
  },
  "items": [
    {
      "main_code": "PROD-001",
      "auxiliary_code": "7861234567890",
      "description": "Servicio de hosting mensual",
      "unit_of_measure": "UNIDAD",
      "quantity": 1,
      "unit_price": 50.0,
      "discount": 0.0,
      "taxes": [
        {
          "code": "2",
          "rate_code": "4",
          "rate": 15.0
        }
      ]
    }
  ],
  "payments": [
    {
      "payment_method": "20",
      "total": 57.5,
      "term": 0,
      "time_unit": "days"
    }
  ],
  "additional_info": {
    "Dirección": "Av. Principal 123",
    "Email": "contabilidad@cliente.com",
    "Teléfono": "0991234567"
  }
}
```

**Response (202 Accepted):**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "access_key": "0404202601179001234500110010010000000421234567817",
    "status": "SENT",
    "issue_date": "2026-04-04",
    "total_amount": 57.5,
    "recipient": {
      "id": "1790012345001",
      "name": "Empresa Cliente S.A."
    },
    "created_at": "2026-04-04T10:30:00Z"
  }
}
```

**Posibles status en la respuesta inicial:**

- `SENT` — XML enviado al SRI exitosamente, pendiente de autorización
- `RECEIVED` — SRI confirmó recepción, pendiente autorización
- `RETRY` — Error temporal con SRI, se reintentará automáticamente
- `REJECTED` — Error de validación del SRI (ver `sri_messages`)

---

#### GET /v1/invoices/:id

Consultar el estado y datos de una factura.

**curl:**

```bash
curl -s http://localhost:8080/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851 \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "access_key": "0404202601179001234500110010010000000421234567817",
    "authorization_number": "0404202601179001234500110010010000000421234567817",
    "status": "AUTHORIZED",
    "issue_date": "2026-04-04",
    "authorization_date": "2026-04-04T10:30:15Z",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "email": "contabilidad@cliente.com"
    },
    "subtotal_before_tax": 50.0,
    "vat_amount": 7.5,
    "total_amount": 57.5,
    "sri_messages": [],
    "downloads": {
      "xml": "/invoices/d290f1ee.../xml",
      "ride": "/invoices/d290f1ee.../ride"
    },
    "retry_count": 0,
    "created_at": "2026-04-04T10:30:00Z",
    "updated_at": "2026-04-04T10:30:15Z"
  }
}
```

---

#### GET /v1/invoices

Listar facturas con filtros y paginación.

**Query params:**

- `status` — filtrar por estado (CREATED, SIGNED, SENT, RECEIVED, AUTHORIZED, NOTIFIED, REJECTED, FAILED, RETRY, VOIDED)
- `date_from` — fecha de emisión desde (YYYY-MM-DD)
- `date_to` — fecha de emisión hasta
- `recipient_id` — filtrar por RUC/cédula del receptor
- `access_key` — buscar por clave de acceso (exacto, 49 dígitos)
- `document_type` — filtrar por tipo de documento (01, 04, 05, etc.)
- `q` — búsqueda libre por nombre o identificación del receptor
- `page` — página (default: 1)
- `per_page` — registros por página (default: 20, max: 100)
- `sort` — campo de ordenamiento (default: `-issue_date`)

**curl (listar con filtros):**

```bash
# Listar facturas autorizadas
curl -s "http://localhost:8080/v1/invoices?status=AUTHORIZED&page=1&per_page=10" \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .

# Filtrar por rango de fechas
curl -s "http://localhost:8080/v1/invoices?date_from=2026-04-01&date_to=2026-04-30" \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .

# Buscar por receptor
curl -s "http://localhost:8080/v1/invoices?recipient_id=1790012345001" \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .

# Búsqueda libre por nombre
curl -s "http://localhost:8080/v1/invoices?q=Empresa%20Cliente" \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

---

#### GET /v1/invoices/:id/xml

Descargar el XML autorizado del comprobante.

**curl:**

```bash
curl -s http://localhost:8080/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851/xml \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -o factura.xml
```

**Response:** `Content-Type: application/xml`

---

#### GET /v1/invoices/:id/ride

Descargar el RIDE (PDF) del comprobante.

**curl:**

```bash
curl -s http://localhost:8080/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851/ride \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -o factura.pdf
```

**Response:** `Content-Type: application/pdf`

---

#### POST /v1/invoices/:id/resend-email

Reenviar el email con RIDE + XML al receptor. Solo disponible para documentos en estado `AUTHORIZED` o `NOTIFIED`.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851/resend-email \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "message": "Email reenviado a contabilidad@cliente.com",
    "sent_at": "2026-04-04T11:00:00Z"
  }
}
```

---

### 2. Notas de Crédito (Credit Notes) — Fase 3

#### POST /credit-notes

```json
{
  "establishment": "001",
  "issue_point": "001",
  "sequence_number": "000000001",
  "issue_date": "2026-04-04",
  "modified_document": {
    "type": "01",
    "number": "001-001-000000042",
    "issue_date": "2026-04-01"
  },
  "reason": "Devolución parcial de mercadería",
  "recipient": { ... },
  "items": [ ... ]
}
```

### 3. Comprobantes de Retención (Withholdings) — Fase 3

#### POST /withholdings

### 4. Notas de Débito (Debit Notes) — Fase 3

#### POST /debit-notes

### 5. Guías de Remisión (Shipping Guides) — Fase 3

#### POST /shipping-guides

### 6. Liquidaciones de Compra (Purchase Settlements) — Fase 3

#### POST /purchase-settlements

---

### 7. Consultas SRI

#### GET /sri/authorize/:accessKey

Consultar el estado de autorización de un comprobante directamente al SRI por su clave de acceso.

**Response (200 OK):**

```json
{
  "data": {
    "access_key": "0404202601...",
    "sri_status": "AUTORIZADO",
    "authorization_date": "2026-04-04T10:30:15Z",
    "authorization_number": "0404202601...",
    "document_xml": "<xml>...</xml>",
    "messages": []
  }
}
```

---

### 8. Envío de XML Raw (Canal Avanzado) — Fase 2

Para integradores que ya generan su propio XML conforme a la ficha técnica del SRI. Key49 valida el XSD, genera la clave de acceso, firma, envía y gestiona todo el ciclo de vida.

Ver ADR-006 en ARCHITECTURE.md para la decisión de diseño.

#### POST /v1/documents/raw

Enviar un comprobante electrónico como XML pre-armado.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/documents/raw \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/xml" \
  -H "X-Document-Type: 01" \
  -H "X-Idempotency-Key: raw-$(date +%s)" \
  -d @factura.xml | jq .
```

**Request:**

```
Content-Type: application/xml
X-Idempotency-Key: unique-string-from-client
X-Document-Type: 01
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<factura id="comprobante" version="2.1.0">
  <infoTributaria>
    <ambiente>1</ambiente>
    <tipoEmision>1</tipoEmision>
    <razonSocial>EMPRESA EMISORA S.A.</razonSocial>
    <ruc>1790012345001</ruc>
    <estab>001</estab>
    <ptoEmi>001</ptoEmi>
    <secuencial>000000042</secuencial>
    <dirMatriz>Quito, Av. Principal 123</dirMatriz>
    <!-- claveAcceso se omite: Key49 la genera -->
    <!-- codDoc se infiere del header X-Document-Type -->
  </infoTributaria>
  <!-- ... resto del XML conforme al XSD del SRI -->
</factura>
```

**Notas importantes:**

- El header `X-Document-Type` es obligatorio: `01` (factura), `03` (liquidación), `04` (NC), `05` (ND), `06` (guía), `07` (retención).
- Key49 **siempre genera** la clave de acceso (49 dígitos con módulo 11), reemplazando cualquier valor que venga en `<claveAcceso>`.
- El XML debe conformar al XSD vigente del tipo de documento. Key49 valida contra XSD antes de procesar.
- Key49 extrae automáticamente del XML: datos del receptor y totales para persistir en la tabla `documents`. Los ítems y pagos no se almacenan en tablas separadas — se preservan en el XML almacenado en MinIO.
- El `<codDoc>` dentro de `<infoTributaria>` debe coincidir con `X-Document-Type`.

**Response (202 Accepted):**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "access_key": "0404202601179001234500110010010000000421234567817",
    "status": "SENT",
    "origin": "XML_RAW",
    "issue_date": "2026-04-04",
    "total_amount": 57.5,
    "created_at": "2026-04-04T10:30:00Z"
  }
}
```

**Errores específicos del endpoint raw:**

| Código HTTP | Error Code                | Descripción                                          |
| ----------- | ------------------------- | ---------------------------------------------------- |
| 400         | `INVALID_XML_STRUCTURE`   | XML mal formado o no parseable                       |
| 400         | `XSD_VALIDATION_FAILED`   | XML no pasa validación contra XSD del SRI            |
| 400         | `DOCUMENT_TYPE_MISMATCH`  | `X-Document-Type` no coincide con `<codDoc>` del XML |
| 400         | `MISSING_DOCUMENT_TYPE`   | Header `X-Document-Type` no proporcionado            |
| 422         | `UNSUPPORTED_XSD_VERSION` | Versión de XSD no soportada por Key49                |

#### GET /v1/documents/raw/:id

Consultar el estado de un documento enviado por XML raw. Misma estructura de respuesta que `GET /v1/invoices/:id`.

**curl:**

```bash
curl -s http://localhost:8080/v1/documents/raw/d290f1ee-6c54-4b01-90e6-d701748f0851 \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

---

### 9. Gestión de Tenant (Perfil)

#### GET /v1/tenant/profile

Obtener datos del tenant autenticado.

**curl:**

```bash
curl -s http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "tenant_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "ruc": "1790016919001",
    "legal_name": "Empresa Demo S.A.",
    "trade_name": "Demo Corp",
    "main_address": "Av. Amazonas N24-345, Quito",
    "environment": "test",
    "webhook_url": null,
    "email_sender_name": null,
    "reply_email": null,
    "certificate": null,
    "schema_name": "tenant_demo",
    "status": "active",
    "created_at": "2026-04-04T00:00:00Z"
  }
}
```

#### PUT /v1/tenant/profile

Actualizar datos del tenant (razón social, dirección, webhook, email). No permite modificar campos administrativos como `status` o `rate_limit_rpm`.

**curl:**

```bash
curl -s -X PUT http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/json" \
  -d '{
    "legal_name": "Empresa Demo S.A.",
    "trade_name": "Demo Corp Actualizado",
    "main_address": "Av. República, Quito",
    "webhook_url": "https://mi-app.com/webhooks/key49",
    "webhook_secret": "mi_secreto_webhook_123",
    "email_sender_name": "Facturación Demo",
    "reply_email": "facturacion@demo.com"
  }' | jq .
```

#### POST /v1/tenant/certificate

Subir o actualizar el certificado .p12.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/tenant/certificate \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -F "certificate=@/ruta/al/certificado.p12" \
  -F "password=contraseña_del_certificado" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "subject": "CN=EMPRESA S.A., O=Security Data",
    "serial": "1234567890ABCDEF",
    "expires_at": "2027-04-04T00:00:00Z",
    "valid": true
  }
}
```

#### GET /v1/tenant/certificate/status

Verificar el estado del certificado (vigencia, expiración).

**curl:**

```bash
curl -s http://localhost:8080/v1/tenant/certificate/status \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "subject": "CN=EMPRESA S.A., O=Security Data",
    "serial": "1234567890ABCDEF",
    "expires_at": "2027-04-04T00:00:00Z",
    "issuer": "CN=Autoridad de Certificación, O=Security Data",
    "days_remaining": 365,
    "valid": true
  }
}
```

---

### 10. Gestión de API Keys

#### POST /v1/tenant/api-keys

Crear una nueva API key para el tenant autenticado. El `raw_key` solo se devuelve una vez en esta respuesta — no es recuperable después.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/tenant/api-keys \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Integración ERP",
    "environment": "test",
    "permissions": "*",
    "expires_at": "2027-12-31T23:59:59Z"
  }' | jq .
```

**Response (201 Created):**

```json
{
  "data": {
    "api_key_id": "c1d2e3f4-a5b6-7890-cdef-123456789012",
    "key_prefix": "fec_test",
    "name": "Integración ERP",
    "environment": "test",
    "permissions": "*",
    "expires_at": "2027-12-31T23:59:59Z",
    "status": "active",
    "raw_key": "fec_test_a1B2c3D4e5F6g7H8i9J0kLmN",
    "created_at": "2026-04-06T10:30:00Z"
  }
}
```

> **Importante**: Guardar el `raw_key` de forma segura. No se puede recuperar después de esta respuesta.

#### GET /v1/tenant/api-keys

Listar todas las API keys del tenant autenticado (sin incluir `raw_key`).

**curl:**

```bash
curl -s http://localhost:8080/v1/tenant/api-keys \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Response (200 OK):**

```json
{
  "data": [
    {
      "api_key_id": "b1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "key_prefix": "fec_test",
      "name": "Dev Local Key",
      "environment": "test",
      "last_used_at": "2026-04-06T10:25:00Z",
      "expires_at": null,
      "status": "active",
      "created_at": "2026-04-04T00:00:00Z"
    }
  ]
}
```

#### GET /v1/tenant/api-keys/:id

Consultar una API key específica.

**curl:**

```bash
curl -s http://localhost:8080/v1/tenant/api-keys/b1b2c3d4-e5f6-7890-abcd-ef1234567890 \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

#### DELETE /v1/tenant/api-keys/:id

Revocar una API key. Una vez revocada, no puede reactivarse.

**curl:**

```bash
curl -s -X DELETE http://localhost:8080/v1/tenant/api-keys/c1d2e3f4-a5b6-7890-cdef-123456789012 \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "api_key_id": "c1d2e3f4-a5b6-7890-cdef-123456789012",
    "key_prefix": "fec_test",
    "name": "Integración ERP",
    "status": "revoked"
  }
}
```

**Errores:** 404 si la key no pertenece al tenant, 409 si ya está revocada.

---

### 11. Administración de Tenants (Admin)

> Estos endpoints requieren autenticación de administrador.

#### POST /v1/admin/tenants

Registrar un nuevo tenant en el sistema.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/admin/tenants \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/json" \
  -d '{
    "ruc": "0991234567001",
    "legal_name": "Nueva Empresa S.A.",
    "trade_name": "NuevaCorp",
    "main_address": "Guayaquil, Av. 9 de Octubre",
    "environment": "test",
    "schema_name": "tenant_nuevacorp"
  }' | jq .
```

> **Nota**: Esto solo registra el tenant. La creación del esquema PostgreSQL y sus tablas es manual (ver [DB-ADMIN.md](DB-ADMIN.md)).

#### GET /v1/admin/tenants

Listar tenants con filtros.

**curl:**

```bash
# Listar todos
curl -s "http://localhost:8080/v1/admin/tenants" \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .

# Filtrar por estado
curl -s "http://localhost:8080/v1/admin/tenants?status=active&page=1&per_page=10" \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

#### GET /v1/admin/tenants/:id

Consultar detalle de un tenant.

#### PUT /v1/admin/tenants/:id

Actualizar datos de un tenant (incluye campos admin como `status`, `rate_limit_rpm`).

#### POST /v1/admin/tenants/:id/certificate

Subir certificado .p12 de un tenant (admin).

#### GET /v1/admin/tenants/:id/certificate/status

Consultar estado del certificado de un tenant (admin).

---

### 12. Anulación Local de Documentos

#### POST /v1/invoices/:id/void

Marcar un documento autorizado como anulado localmente. Key49 NO anula en el SRI — eso lo hace el contribuyente en el portal del SRI. Key49 solo registra la anulación local para trazabilidad interna.

> **Requisitos**: el documento debe estar en estado `AUTHORIZED` o `NOTIFIED`. Solo se puede anular hasta el día 7 del mes siguiente a la emisión. Facturas a consumidor final (`recipient_id_type = "07"`) no pueden anularse.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851/void \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Error en datos del receptor"}' | jq .
```

**Request:**

```json
{
  "reason": "Error en datos del receptor"
}
```

**Response (200 OK):**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "status": "VOIDED",
    "voided_at": "2026-04-05T14:00:00Z",
    "void_reason": "Error en datos del receptor",
    "access_key": "0404202601179001234500110010010000000421234567817"
  }
}
```

**Errores:**

| Código HTTP | Error Code                    | Descripción                                       |
| ----------- | ----------------------------- | ------------------------------------------------- |
| 409         | `INVALID_STATE_TRANSITION`    | Documento no está en estado AUTHORIZED o NOTIFIED |
| 422         | `VOID_PERIOD_EXPIRED`         | Superó el día 7 del mes siguiente a la emisión    |
| 422         | `FINAL_CONSUMER_NOT_VOIDABLE` | Facturas a consumidor final no pueden anularse    |

---

### 13. Métricas

#### GET /v1/metrics/summary

Resumen de actividad del tenant: documentos hoy, este mes, certificado y última factura.

**curl:**

```bash
curl -s http://localhost:8080/v1/metrics/summary \
  -H "Authorization: Bearer fec_test_DemoKey49DevLocalTest00" | jq .
```

```json
{
  "data": {
    "today": {
      "total": 45,
      "authorized": 42,
      "rejected": 2,
      "pending": 1
    },
    "month": {
      "total": 1250,
      "authorized": 1230,
      "rejected": 15,
      "failed": 5
    },
    "certificate_expires_in_days": 180,
    "last_invoice_at": "2026-04-04T10:30:00Z"
  }
}
```

---

## Health Checks y Observabilidad

### GET /q/health

Health check general (readiness + liveness combinados).

**curl:**

```bash
curl -s http://localhost:8080/q/health | jq .
```

### GET /q/health/ready

Solo readiness: verifica que PostgreSQL, Redis, RabbitMQ y MinIO estén accesibles. Incluye alerta si un certificado vence en menos de 30 días.

```bash
curl -s http://localhost:8080/q/health/ready | jq .
```

### GET /q/health/live

Solo liveness: verifica que los endpoints WSDL del SRI estén accesibles (Recepción y Autorización).

```bash
curl -s http://localhost:8080/q/health/live | jq .
```

---

## Portal Web

El portal web es una interfaz de solo lectura para consultar documentos. Usa server-side rendering con Qute + HTMX + Pico CSS.

### Rutas del Portal

| Ruta                           | Método | Descripción                                                        |
| ------------------------------ | ------ | ------------------------------------------------------------------ |
| `/portal/login`                | GET    | Formulario de login (API key)                                      |
| `/portal/login`                | POST   | Procesar login, crear sesión Redis                                 |
| `/portal/logout`               | GET    | Cerrar sesión, eliminar cookie                                     |
| `/portal/`                     | GET    | Dashboard: lista de documentos con filtros y paginación            |
| `/portal/documents/:id`        | GET    | Detalle del documento: estado, timeline, totales, enlaces descarga |
| `/portal/documents/:id/status` | GET    | Fragmento HTML del badge de estado (para polling HTMX)             |

### Flujo de autenticación del Portal

1. El usuario accede a `/portal/login`
2. Ingresa su API key (ej: `fec_test_DemoKey49DevLocalTest00`)
3. El sistema valida la API key, crea una sesión en Redis (TTL 30 min)
4. Establece cookie `KEY49_SESSION` (HttpOnly, SameSite=Lax)
5. Redirige al dashboard (`/portal/`)
6. Cada request renueva el TTL de la sesión
7. Logout elimina la sesión de Redis y la cookie

### Stack del Portal

- **Pico CSS v2**: estilos semánticos sin clases
- **HTMX v2.0.4**: interactividad sin JavaScript manual
- **Polling**: `hx-trigger="every 5s"` para actualizar estado de documentos en proceso

---

## OpenAPI / Swagger

### GET /q/openapi

Especificación OpenAPI 3.x en formato YAML/JSON.

```bash
curl -s http://localhost:8080/q/openapi | head -50
```

---

## Webhooks

Key49 envía webhooks POST al `webhook_url` del tenant cuando un documento cambia de estado.

### Headers del Webhook

```
Content-Type: application/json
X-Key49-Signature: sha256=abc123...    (HMAC-SHA256 del body con webhook_secret)
X-Key49-Event: document.authorized
X-Key49-Delivery: del_uuid
X-Key49-Timestamp: 2026-04-04T10:30:15Z
```

### Payload del Webhook

```json
{
  "event": "document.authorized",
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "access_key": "0404202601...",
    "authorization_number": "0404202601...",
    "status": "AUTHORIZED",
    "authorization_date": "2026-04-04T10:30:15Z",
    "total_amount": 57.5,
    "recipient": {
      "id": "1790012345001",
      "name": "Empresa Cliente S.A."
    }
  },
  "timestamp": "2026-04-04T10:30:15Z"
}
```

### Eventos de Webhook

| Evento                  | Descripción                                         |
| ----------------------- | --------------------------------------------------- |
| `document.authorized`   | Comprobante autorizado por el SRI                   |
| `document.rejected`     | Comprobante rechazado por el SRI (error de negocio) |
| `document.failed`       | Comprobante falló después de agotar reintentos      |
| `document.email_sent`   | Email con RIDE enviado al receptor                  |
| `document.email_failed` | Fallo en envío de email                             |
| `certificate.expiring`  | Certificado expira en menos de 30 días              |

### Reintentos de Webhook

- 3 intentos con backoff: 10s, 60s, 300s
- Se espera HTTP 2xx como confirmación de entrega
- Si falla los 3 intentos, se marca como `failed` en `webhook_deliveries`

### Verificación de Firma del Webhook

El integrador debe verificar la firma HMAC-SHA256 del body usando su `webhook_secret`:

```
expected = HMAC-SHA256(webhook_secret, raw_body)
actual   = header X-Key49-Signature (sin prefijo "sha256=")
```

Rechazar el webhook si las firmas no coinciden.

---

## Catálogo de Errores

### Errores Generales

| Código HTTP | Error Code                   | Descripción                                                                                                                                                                                                             |
| ----------- | ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 400         | `VALIDATION_ERROR`           | Uno o más campos del request son inválidos                                                                                                                                                                              |
| 400         | `INVALID_ESTABLISHMENT`      | `establishment` no tiene formato 3 dígitos numéricos                                                                                                                                                                    |
| 400         | `INVALID_ISSUE_POINT`        | `issue_point` no tiene formato 3 dígitos numéricos                                                                                                                                                                      |
| 400         | `INVALID_SEQUENCE_NUMBER`    | `sequence_number` no tiene formato 9 dígitos numéricos                                                                                                                                                                  |
| 400         | `INVALID_RECIPIENT_ID`       | Identificación del receptor no pasa validación de formato (RUC/cédula)                                                                                                                                                  |
| 400         | `INVALID_ISSUE_DATE`         | `issue_date` no es la fecha actual (America/Guayaquil)                                                                                                                                                                  |
| 401         | `UNAUTHORIZED`               | API key no proporcionado o inválido                                                                                                                                                                                     |
| 401         | `API_KEY_EXPIRED`            | API key expirado                                                                                                                                                                                                        |
| 401         | `API_KEY_REVOKED`            | API key revocado                                                                                                                                                                                                        |
| 403         | `TENANT_SUSPENDED`           | Tenant suspendido, no puede emitir documentos                                                                                                                                                                           |
| 404         | `DOCUMENT_NOT_FOUND`         | Documento no encontrado                                                                                                                                                                                                 |
| 409         | `DUPLICATE_DOCUMENT`         | Documento activo/completado ya existe con el mismo tipo, establecimiento, punto de emisión y secuencial. Si el documento existente estaba en estado `REJECTED` o `FAILED`, se recicla automáticamente y se retorna 202. |
| 409         | `IDEMPOTENCY_CONFLICT`       | `X-Idempotency-Key` ya usado con un request distinto                                                                                                                                                                    |
| 409         | `INVALID_STATE_TRANSITION`   | Transición de estado no permitida (ver state machine en ARCHITECTURE.md)                                                                                                                                                |
| 422         | `CERTIFICATE_NOT_CONFIGURED` | Tenant no tiene certificado .p12 configurado                                                                                                                                                                            |
| 422         | `CERTIFICATE_EXPIRED`        | Certificado .p12 del tenant está expirado                                                                                                                                                                               |
| 429         | `RATE_LIMIT_EXCEEDED`        | Se excedió el límite de requests por minuto                                                                                                                                                                             |
| 500         | `INTERNAL_ERROR`             | Error interno de Key49                                                                                                                                                                                                  |
| 502         | `SRI_UNAVAILABLE`            | No se pudo contactar al SRI (timeout o conexión rechazada)                                                                                                                                                              |

### Errores de XML Raw (POST /documents/raw)

| Código HTTP | Error Code                | Descripción                                          |
| ----------- | ------------------------- | ---------------------------------------------------- |
| 400         | `INVALID_XML_STRUCTURE`   | XML mal formado o no parseable                       |
| 400         | `XSD_VALIDATION_FAILED`   | XML no pasa validación contra XSD del SRI            |
| 400         | `DOCUMENT_TYPE_MISMATCH`  | `X-Document-Type` no coincide con `<codDoc>` del XML |
| 400         | `MISSING_DOCUMENT_TYPE`   | Header `X-Document-Type` no proporcionado            |
| 422         | `UNSUPPORTED_XSD_VERSION` | Versión de XSD no soportada por Key49                |

### Errores de Anulación (POST /invoices/:id/void)

| Código HTTP | Error Code                    | Descripción                                       |
| ----------- | ----------------------------- | ------------------------------------------------- |
| 409         | `INVALID_STATE_TRANSITION`    | Documento no está en estado AUTHORIZED o NOTIFIED |
| 422         | `VOID_PERIOD_EXPIRED`         | Superó el día 7 del mes siguiente a la emisión    |
| 422         | `FINAL_CONSUMER_NOT_VOIDABLE` | Facturas a consumidor final no pueden anularse    |

### Formato de Error con Detalles de Validación

Cuando `code = "VALIDATION_ERROR"`, el campo `details` contiene el desglose por campo:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request contiene campos inválidos",
    "details": [
      {
        "field": "recipient.id",
        "message": "RUC debe tener 13 dígitos",
        "code": "INVALID_FORMAT"
      },
      {
        "field": "sequence_number",
        "message": "Debe tener exactamente 9 dígitos numéricos",
        "code": "INVALID_FORMAT"
      }
    ]
  },
  "meta": {
    "request_id": "req_abc123"
  }
}
```

### Códigos de Detalle de Validación

| Code             | Descripción                                                    |
| ---------------- | -------------------------------------------------------------- |
| `REQUIRED`       | Campo obligatorio no proporcionado                             |
| `INVALID_FORMAT` | Formato incorrecto (ej: RUC no tiene 13 dígitos)               |
| `INVALID_VALUE`  | Valor no reconocido (ej: `tax.code` no existe en catálogo SRI) |
| `OUT_OF_RANGE`   | Valor fuera de rango permitido (ej: `quantity` negativa)       |
| `TOO_LONG`       | Valor excede longitud máxima                                   |

---

## Códigos de Error

| Código HTTP | Error Code            | Descripción                         |
| ----------- | --------------------- | ----------------------------------- |
| 400         | `VALIDATION_ERROR`    | Datos de entrada inválidos          |
| 400         | `INVALID_XML`         | XML generado no pasa validación XSD |
| 401         | `UNAUTHORIZED`        | API key inválido o expirado         |
| 403         | `FORBIDDEN`           | Sin permisos para esta operación    |
| 404         | `NOT_FOUND`           | Recurso no encontrado               |
| 409         | `DUPLICATE`           | Idempotency key ya procesada        |
| 422         | `CERTIFICATE_EXPIRED` | Certificado .p12 expirado           |
| 422         | `CERTIFICATE_MISSING` | No hay certificado configurado      |
| 422         | `SRI_REJECTED`        | SRI rechazó el comprobante          |
| 429         | `RATE_LIMITED`        | Se excedió el rate limit            |
| 500         | `INTERNAL_ERROR`      | Error interno del servidor          |
| 502         | `SRI_UNAVAILABLE`     | SRI no disponible (se reintentará)  |
| 503         | `SERVICE_UNAVAILABLE` | Servicio en mantenimiento           |

## Códigos de Error del SRI (referencia)

| Código | Descripción                         | Acción                               |
| ------ | ----------------------------------- | ------------------------------------ |
| 35     | Documento ya registrado             | No reintentar, marcar como duplicado |
| 43     | Clave de acceso registrada          | Regenerar clave, reintentar          |
| 45     | Fecha fuera de rango permitido      | No reintentar, notificar             |
| 52     | Error en estructura del comprobante | No reintentar, revisar XML           |
| 65     | Fecha de emisión mayor a la actual  | No reintentar, notificar             |
| 70     | Clave de acceso inválida            | Regenerar clave, reintentar          |

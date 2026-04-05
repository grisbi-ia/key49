# API REST — Key49

## Base URL

```
Producción: https://api.key49.ec/v1
Pruebas:    https://sandbox.key49.ec/v1
```

## Autenticación

Todas las peticiones requieren un API Key en el header `Authorization`:

```
Authorization: Bearer fec_live_xxxxxxxxxxxxxxxxxxxx
```

Los API keys tienen prefijo `fec_live_` (producción) o `fec_test_` (pruebas).

## Rate Limiting

- Default: 100 requests/minuto por API key
- Headers de respuesta: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
- Al exceder: HTTP 429 con header `Retry-After`

## Idempotencia

Todas las operaciones de creación soportan idempotencia via header:

```
X-Idempotency-Key: unique-string-from-client
```

Si se envía el mismo key, se retorna el resultado original sin reprocesar.

## Headers de Respuesta Comunes

Todas las respuestas incluyen los siguientes headers:

```
X-Request-Id: req_abc123              # ID único de Key49 para trazabilidad
X-Trace-Id: 4bf92f3577b347a8...       # OpenTelemetry trace ID (si habilitado)
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

#### POST /invoices

Crear y enviar una factura electrónica al SRI.

> **Nota**: Los campos `establishment`, `issue_point` y `sequence_number` son responsabilidad del cliente. Key49 no gestiona secuenciales. La `issue_date` debe ser la fecha del día actual (emisión en tiempo real).
>
> **Almacenamiento**: Key49 persiste los datos resumen del documento (receptor, totales, estado) en la tabla `documents`. Los ítems y formas de pago NO se almacenan en tablas separadas — se preservan en el `request_payload` original y en los XML almacenados en MinIO.

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

#### GET /invoices/:id

Consultar el estado y datos de una factura.

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

#### GET /invoices

Listar facturas con filtros y paginación.

**Query params:**

- `status` — filtrar por estado (AUTHORIZED, REJECTED, VOIDED, etc.)
- `date_from` — fecha de emisión desde (YYYY-MM-DD)
- `date_to` — fecha de emisión hasta
- `recipient_id` — filtrar por RUC/cédula del receptor
- `access_key` — buscar por clave de acceso (exacto, 49 dígitos)
- `document_type` — filtrar por tipo de documento (01, 04, 05, etc.)
- `page` — página (default: 1)
- `per_page` — registros por página (default: 20, max: 100)
- `sort` — campo de ordenamiento (default: `-issue_date`)

---

#### GET /invoices/:id/xml

Descargar el XML autorizado del comprobante.

**Response:** `Content-Type: application/xml`

---

#### GET /invoices/:id/ride

Descargar el RIDE (PDF) del comprobante.

**Response:** `Content-Type: application/pdf`

---

#### POST /invoices/:id/resend-email

Reenviar el email con RIDE + XML al receptor.

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

#### POST /documents/raw

Enviar un comprobante electrónico como XML pre-armado.

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

#### GET /documents/raw/:id

Consultar el estado de un documento enviado por XML raw. Misma estructura de respuesta que `GET /invoices/:id`.

---

### 9. Gestión de Tenant (Fase 2)

#### GET /tenant/profile

Obtener datos del tenant autenticado.

#### PUT /tenant/profile

Actualizar datos del tenant (razón social, dirección, webhook, etc.).

#### POST /tenant/certificate

Subir o actualizar el certificado .p12.

```
Content-Type: multipart/form-data

certificate: (archivo .p12)
password: (contraseña del certificado)
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

#### GET /tenant/certificate/status

Verificar el estado del certificado (vigencia, expiración).

---

### 10. Anulación Local de Documentos

#### POST /invoices/:id/void

Marcar un documento autorizado como anulado localmente. Key49 NO anula en el SRI — eso lo hace el contribuyente en el portal del SRI. Key49 solo registra la anulación local para trazabilidad interna.

> **Requisitos**: el documento debe estar en estado `AUTHORIZED` o `NOTIFIED`. Solo se puede anular hasta el día 7 del mes siguiente a la emisión. Facturas a consumidor final (`recipient_id_type = "07"`) no pueden anularse.

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

### 11. Dashboard / Métricas

#### GET /metrics/summary

Resumen de actividad del tenant.

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

| Código HTTP | Error Code                   | Descripción                                                                              |
| ----------- | ---------------------------- | ---------------------------------------------------------------------------------------- |
| 400         | `VALIDATION_ERROR`           | Uno o más campos del request son inválidos                                               |
| 400         | `INVALID_ESTABLISHMENT`      | `establishment` no tiene formato 3 dígitos numéricos                                     |
| 400         | `INVALID_ISSUE_POINT`        | `issue_point` no tiene formato 3 dígitos numéricos                                       |
| 400         | `INVALID_SEQUENCE_NUMBER`    | `sequence_number` no tiene formato 9 dígitos numéricos                                   |
| 400         | `INVALID_RECIPIENT_ID`       | Identificación del receptor no pasa validación de formato (RUC/cédula)                   |
| 400         | `INVALID_ISSUE_DATE`         | `issue_date` no es la fecha actual (America/Guayaquil)                                   |
| 401         | `UNAUTHORIZED`               | API key no proporcionado o inválido                                                      |
| 401         | `API_KEY_EXPIRED`            | API key expirado                                                                         |
| 401         | `API_KEY_REVOKED`            | API key revocado                                                                         |
| 403         | `TENANT_SUSPENDED`           | Tenant suspendido, no puede emitir documentos                                            |
| 404         | `DOCUMENT_NOT_FOUND`         | Documento no encontrado                                                                  |
| 409         | `DUPLICATE_DOCUMENT`         | Ya existe un documento con el mismo tipo, establecimiento, punto de emisión y secuencial |
| 409         | `IDEMPOTENCY_CONFLICT`       | `X-Idempotency-Key` ya usado con un request distinto                                     |
| 409         | `INVALID_STATE_TRANSITION`   | Transición de estado no permitida (ver state machine en ARCHITECTURE.md)                 |
| 422         | `CERTIFICATE_NOT_CONFIGURED` | Tenant no tiene certificado .p12 configurado                                             |
| 422         | `CERTIFICATE_EXPIRED`        | Certificado .p12 del tenant está expirado                                                |
| 429         | `RATE_LIMIT_EXCEEDED`        | Se excedió el límite de requests por minuto                                              |
| 500         | `INTERNAL_ERROR`             | Error interno de Key49                                                                   |
| 502         | `SRI_UNAVAILABLE`            | No se pudo contactar al SRI (timeout o conexión rechazada)                               |

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

# API REST — Key49

Plataforma de facturación electrónica SRI Ecuador. Esta referencia cubre todos los endpoints disponibles, ejemplos de request/response y catálogo de errores.

---

## Base URL

```
Desarrollo:  http://localhost:8080/v1
Sandbox:     https://sandbox.key49.ec/v1
Producción:  https://api.key49.ec/v1
```

---

## Autenticación

Todas las peticiones a `/v1/*` requieren un API Key en el header `Authorization`:

```
Authorization: Bearer k49_xxxxxxxxxxxxxxxxxxxxxxxx
```

Los API keys tienen el prefijo `k49_` seguido de 24 caracteres aleatorios. El ambiente de operación (SRI pruebas o producción) lo determina la configuración del tenant, no el API key.

**Ejemplo rápido:**

```bash
curl -s http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Paths públicos** (sin autenticación): `/q/*`, `/portal/login`, `/portal/register/*`, `/openapi`, `/swagger-ui`.

---

## Rate Limiting

Los límites se aplican por API key, según el plan del tenant:

| Plan       | Escritura (POST) | Lectura (GET) |
| ---------- | :--------------: | :-----------: |
| DEMO       | 10 rpm           | 30 rpm        |
| STARTER    | 30 rpm           | 100 rpm       |
| BUSINESS   | 60 rpm           | 200 rpm       |
| ENTERPRISE | 200 rpm          | 600 rpm       |

**Headers de respuesta:**

```
X-RateLimit-Limit: 30
X-RateLimit-Remaining: 25
X-RateLimit-Reset: 1712234567
```

Al exceder el límite: HTTP **429** con header `Retry-After` y error code `RATE_LIMIT_EXCEEDED`.

---

## Idempotencia

Todas las operaciones de creación aceptan:

```
X-Idempotency-Key: <string único del cliente>
```

Si se envía el mismo key, se retorna el resultado original sin reprocesar. Si el mismo key llega con un body diferente: HTTP **409** `IDEMPOTENCY_CONFLICT`.

---

## Formato de Respuestas

### Éxito

```json
{
  "data": { ... },
  "meta": {
    "request_id": "req_abc123def456",
    "timestamp": "2026-04-14T15:30:00Z"
  }
}
```

### Lista paginada

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

### Error

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
      }
    ]
  },
  "meta": {
    "request_id": "req_abc123def456"
  }
}
```

---

## Catálogos SRI (referencia rápida)

### Tipos de identificación (`id_type`)

| Código | Tipo            | Longitud |
| ------ | --------------- | -------- |
| `04`   | RUC             | 13 díg.  |
| `05`   | Cédula          | 10 díg.  |
| `06`   | Pasaporte       | 3-20 car.|
| `07`   | Consumidor final| 13 díg.  |

### Tipos de impuesto (`code`)

| Código | Tipo   |
| ------ | ------ |
| `2`    | IVA    |
| `3`    | ICE    |
| `5`    | IRBPNR |

### Tarifas IVA (`rate_code`)

| Código | Tarifa |
| ------ | ------ |
| `0`    | 0%     |
| `2`    | 12%    |
| `3`    | 14%    |
| `4`    | 15%    |
| `6`    | No objeto IVA |
| `7`    | Exento |

### Formas de pago (`payment_method`)

| Código | Forma de pago       |
| ------ | ------------------- |
| `01`   | Efectivo            |
| `15`   | Compensación deudas |
| `16`   | Tarjeta de débito   |
| `17`   | Dinero electrónico  |
| `18`   | Tarjeta prepago     |
| `19`   | Tarjeta de crédito  |
| `20`   | Transferencia       |
| `21`   | Cheque              |

### Tipos de comprobante (`document_type`)

| Código | Tipo                         |
| ------ | ---------------------------- |
| `01`   | Factura                      |
| `03`   | Liquidación de compra        |
| `04`   | Nota de crédito              |
| `05`   | Nota de débito               |
| `06`   | Guía de remisión             |
| `07`   | Comprobante de retención     |

---

## Estados de un Documento

| Estado       | Descripción                                                  |
| ------------ | ------------------------------------------------------------ |
| `CREATED`    | Documento creado, pendiente de firma                         |
| `SIGNED`     | XML firmado con XAdES-BES, pendiente de envío al SRI         |
| `SENT`       | Enviado al SRI por SOAP, pendiente de autorización           |
| `RECEIVED`   | SRI confirmó recepción, pendiente de consulta de autorización|
| `AUTHORIZED` | Autorizado por el SRI                                        |
| `NOTIFIED`   | Email con RIDE + XML enviado al receptor                     |
| `REJECTED`   | Rechazado por el SRI (error de validación)                   |
| `RETRY`      | Error temporal, reintentando con backoff                     |
| `FAILED`     | Reintentos agotados (requiere intervención manual)           |
| `VOIDED`     | Anulado localmente (el contribuyente debe anular en el SRI)  |

---

## 1. Facturas — `POST /v1/invoices`

Emite una factura electrónica tipo `01`.

> La `issue_date` debe ser la fecha actual en zona horaria `America/Guayaquil`. Key49 valida esto antes de procesar. Los secuenciales (`sequence_number`) son responsabilidad del cliente.

**curl:**

```bash
curl -s -X POST http://localhost:8080/v1/invoices \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: inv-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "issue_date": "2026-04-14",
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
        "unit_price": 50.00,
        "discount": 0.00,
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
        "total": 57.50,
        "term": 0,
        "time_unit": "days"
      }
    ],
    "additional_info": {
      "Orden de compra": "OC-2026-0042",
      "Contrato": "CT-2026-001"
    }
  }' | jq .
```

**Request — campos:**

| Campo                        | Tipo            | Req | Descripción                                    |
| ---------------------------- | --------------- | :-: | ---------------------------------------------- |
| `establishment`              | string `^\d{3}$`| ✓   | Código establecimiento (3 dígitos)             |
| `issue_point`                | string `^\d{3}$`| ✓   | Punto de emisión (3 dígitos)                   |
| `sequence_number`            | string `^\d{9}$`| ✓   | Secuencial (9 dígitos, ej: `000000042`)        |
| `issue_date`                 | date `YYYY-MM-DD`| ✓  | Fecha de emisión (debe ser hoy en EC)          |
| `recipient.id_type`          | string          | ✓   | Código tipo identificación (ver catálogo)      |
| `recipient.id`               | string          | ✓   | RUC, cédula o pasaporte del receptor           |
| `recipient.name`             | string          | ✓   | Razón social o nombre del receptor             |
| `recipient.address`          | string          |     | Dirección del receptor                         |
| `recipient.email`            | string          |     | Email para envío del RIDE                      |
| `recipient.phone`            | string          |     | Teléfono del receptor                          |
| `items[].main_code`          | string          | ✓   | Código principal del producto/servicio         |
| `items[].auxiliary_code`     | string          |     | Código auxiliar (ej: código de barras)         |
| `items[].description`        | string          | ✓   | Descripción del ítem                           |
| `items[].unit_of_measure`    | string          |     | Unidad de medida (ej: `UNIDAD`, `KG`)          |
| `items[].quantity`           | decimal         | ✓   | Cantidad                                       |
| `items[].unit_price`         | decimal         | ✓   | Precio unitario sin IVA                        |
| `items[].discount`           | decimal         |     | Descuento en valor absoluto (default: 0)       |
| `items[].taxes[].code`       | string          | ✓   | Tipo de impuesto (`2`=IVA, `3`=ICE)            |
| `items[].taxes[].rate_code`  | string          | ✓   | Código de tarifa del catálogo SRI              |
| `items[].taxes[].rate`       | decimal         | ✓   | Porcentaje (ej: `15.0`)                        |
| `payments[].payment_method`  | string          | ✓   | Código de forma de pago (ver catálogo)         |
| `payments[].total`           | decimal         | ✓   | Monto de este medio de pago                    |
| `payments[].term`            | integer         |     | Plazo en unidades (default: 0)                 |
| `payments[].time_unit`       | string          |     | Unidad del plazo: `days`, `months`             |
| `additional_info`            | map string      |     | Información adicional (clave-valor libre)      |

**Response (202 Accepted):**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "access_key": "1404202601179001234500110010010000000421234567817",
    "status": "SENT",
    "issue_date": "2026-04-14",
    "total_amount": 57.50,
    "recipient": {
      "id": "1790012345001",
      "name": "Empresa Cliente S.A."
    },
    "created_at": "2026-04-14T20:30:00Z"
  },
  "meta": {
    "request_id": "req_abc123def456",
    "timestamp": "2026-04-14T20:30:00Z"
  }
}
```

### `GET /v1/invoices/:id`

```bash
curl -s http://localhost:8080/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851 \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Response (200 OK) — documento autorizado:**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "access_key": "1404202601179001234500110010010000000421234567817",
    "authorization_number": "1404202601179001234500110010010000000421234567817",
    "status": "AUTHORIZED",
    "issue_date": "2026-04-14",
    "authorization_date": "2026-04-14T20:30:15Z",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "email": "contabilidad@cliente.com"
    },
    "subtotal_before_tax": 50.00,
    "vat_amount": 7.50,
    "total_amount": 57.50,
    "sri_messages": [],
    "downloads": {
      "xml": "/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851/xml",
      "ride": "/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851/ride"
    },
    "retry_count": 0,
    "created_at": "2026-04-14T20:30:00Z",
    "updated_at": "2026-04-14T20:30:15Z"
  }
}
```

### `GET /v1/invoices`

**Query params:**

| Param          | Descripción                                              |
| -------------- | -------------------------------------------------------- |
| `status`       | Estado del documento (ver tabla de estados)              |
| `date_from`    | Fecha desde `YYYY-MM-DD`                                 |
| `date_to`      | Fecha hasta `YYYY-MM-DD`                                 |
| `recipient_id` | RUC/cédula del receptor                                  |
| `access_key`   | Clave de acceso exacta (49 dígitos)                      |
| `document_type`| Tipo de comprobante (`01`, `04`, `05`, etc.)             |
| `q`            | Búsqueda libre por nombre o identificación del receptor  |
| `page`         | Página (default: 1)                                      |
| `per_page`     | Registros por página (default: 20, máx: 100)             |
| `sort`         | Ordenamiento (default: `-issue_date`)                    |

```bash
# Facturas autorizadas
curl -s "http://localhost:8080/v1/invoices?status=AUTHORIZED&page=1&per_page=10" \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .

# Por rango de fechas
curl -s "http://localhost:8080/v1/invoices?date_from=2026-04-01&date_to=2026-04-30" \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .

# Por receptor
curl -s "http://localhost:8080/v1/invoices?recipient_id=1790012345001" \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

### `GET /v1/invoices/:id/xml`

Descarga el XML autorizado firmado con XAdES-BES.

```bash
curl -s http://localhost:8080/v1/invoices/d290f1ee.../xml \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -o factura.xml
```

Respuesta: `Content-Type: application/xml`

### `GET /v1/invoices/:id/ride`

Descarga el RIDE (PDF) del comprobante.

```bash
curl -s http://localhost:8080/v1/invoices/d290f1ee.../ride \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -o factura.pdf
```

Respuesta: `Content-Type: application/pdf`

### `POST /v1/invoices/:id/resend-email`

Reenvía el email con RIDE + XML al receptor. Solo disponible en estado `AUTHORIZED` o `NOTIFIED`.

```bash
curl -s -X POST http://localhost:8080/v1/invoices/d290f1ee.../resend-email \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "message": "Email reenviado exitosamente",
    "sent_at": "2026-04-14T21:00:00Z"
  }
}
```

### `POST /v1/invoices/:id/void`

Anula localmente un documento. Key49 **no anula en el SRI** — eso lo hace el contribuyente en el portal del SRI. Solo registra la anulación para trazabilidad interna.

> **Restricciones:** estado debe ser `AUTHORIZED` o `NOTIFIED`; plazo máximo hasta el día 7 del mes siguiente; facturas a consumidor final (`id_type = "07"`) no pueden anularse.

```bash
curl -s -X POST http://localhost:8080/v1/invoices/d290f1ee.../void \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Error en datos del receptor"}' | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "status": "VOIDED",
    "voided_at": "2026-04-14T21:05:00Z",
    "void_reason": "Error en datos del receptor",
    "access_key": "1404202601179001234500110010010000000421234567817"
  }
}
```

---

## 2. Notas de Crédito — `POST /v1/credit-notes`

Emite una nota de crédito electrónica tipo `04` para modificar o anular parcialmente una factura previa.

```bash
curl -s -X POST http://localhost:8080/v1/credit-notes \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: nc-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-14",
    "modified_document_code": "01",
    "modified_document_number": "001-001-000000042",
    "modified_document_date": "2026-04-10",
    "reason": "Devolución parcial de mercadería defectuosa",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "email": "contabilidad@cliente.com",
      "phone": "0991234567"
    },
    "items": [
      {
        "internal_code": "PROD-001",
        "additional_code": "7861234567890",
        "description": "Devolución servicio de hosting",
        "quantity": 1,
        "unit_price": 20.00,
        "discount": 0.00,
        "taxes": [
          {
            "code": "2",
            "rate_code": "4",
            "rate": 15.0
          }
        ]
      }
    ],
    "additional_info": {
      "Motivo": "Producto defectuoso"
    }
  }' | jq .
```

**Request — campos:**

| Campo                           | Tipo   | Req | Descripción                                               |
| ------------------------------- | ------ | :-: | --------------------------------------------------------- |
| `establishment`                 | string | ✓   | Código establecimiento (3 dígitos)                        |
| `issue_point`                   | string | ✓   | Punto de emisión (3 dígitos)                              |
| `sequence_number`               | string | ✓   | Secuencial (9 dígitos)                                    |
| `issue_date`                    | date   | ✓   | Fecha de emisión (debe ser hoy en EC)                     |
| `modified_document_code`        | string | ✓   | Tipo del comprobante modificado (ej: `01`)                |
| `modified_document_number`      | string | ✓   | Número del comprobante modificado (`estab-pto-secuencial`)|
| `modified_document_date`        | date   | ✓   | Fecha del comprobante modificado                          |
| `reason`                        | string | ✓   | Motivo de la nota de crédito                              |
| `recipient`                     | object | ✓   | Datos del receptor (igual que factura)                    |
| `items[].internal_code`         | string | ✓   | Código interno del ítem                                   |
| `items[].additional_code`       | string |     | Código adicional                                          |
| `items[].description`           | string | ✓   | Descripción del ítem devuelto/modificado                  |
| `items[].quantity`              | decimal| ✓   | Cantidad                                                  |
| `items[].unit_price`            | decimal| ✓   | Precio unitario (sin IVA)                                 |
| `items[].discount`              | decimal|     | Descuento                                                 |
| `items[].taxes`                 | array  | ✓   | Impuestos (igual estructura que factura)                  |
| `additional_info`               | map    |     | Información adicional libre                               |

**Response (202 Accepted):** igual estructura que factura pero `document_type: "04"`.

Los endpoints `GET /v1/credit-notes`, `GET /v1/credit-notes/:id`, `/xml`, `/ride`, `/resend-email` y `/void` funcionan igual que en facturas.

---

## 3. Notas de Débito — `POST /v1/debit-notes`

Emite una nota de débito electrónica tipo `05`. A diferencia de la nota de crédito, usa **motivos con valor** en lugar de ítems con cantidades.

```bash
curl -s -X POST http://localhost:8080/v1/debit-notes \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: nd-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-14",
    "modified_document_code": "01",
    "modified_document_number": "001-001-000000042",
    "modified_document_date": "2026-04-10",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "email": "contabilidad@cliente.com",
      "phone": "0991234567"
    },
    "reasons": [
      {
        "description": "Ajuste por diferencia de precio",
        "amount": 10.00
      },
      {
        "description": "Interés por mora (30 días)",
        "amount": 2.50
      }
    ],
    "taxes": [
      {
        "code": "2",
        "rate_code": "4",
        "rate": 15.0
      }
    ],
    "payments": [
      {
        "payment_method": "20",
        "total": 14.38,
        "term": 15,
        "time_unit": "days"
      }
    ],
    "additional_info": {
      "Referencia": "Factura 001-001-000000042"
    }
  }' | jq .
```

**Request — campos específicos de nota de débito:**

| Campo                          | Tipo   | Req | Descripción                                       |
| ------------------------------ | ------ | :-: | ------------------------------------------------- |
| `establishment`                | string | ✓   | Código establecimiento (3 dígitos)                |
| `issue_point`                  | string | ✓   | Punto de emisión (3 dígitos)                      |
| `sequence_number`              | string | ✓   | Secuencial (9 dígitos)                            |
| `issue_date`                   | date   | ✓   | Fecha de emisión (debe ser hoy en EC)             |
| `modified_document_code`       | string | ✓   | Tipo del comprobante modificado                   |
| `modified_document_number`     | string | ✓   | Número del comprobante modificado                 |
| `modified_document_date`       | date   | ✓   | Fecha del comprobante modificado                  |
| `recipient`                    | object | ✓   | Datos del receptor                                |
| `reasons[].description`        | string | ✓   | Descripción del motivo de débito                  |
| `reasons[].amount`             | decimal| ✓   | Valor del motivo (sin IVA)                        |
| `taxes[].code`                 | string | ✓   | Tipo de impuesto                                  |
| `taxes[].rate_code`            | string | ✓   | Código de tarifa                                  |
| `taxes[].rate`                 | decimal| ✓   | Porcentaje                                        |
| `payments[].payment_method`    | string | ✓   | Forma de pago                                     |
| `payments[].total`             | decimal| ✓   | Monto                                             |
| `payments[].term`              | integer|     | Plazo                                             |
| `payments[].time_unit`         | string |     | Unidad del plazo (`days`, `months`)               |
| `additional_info`              | map    |     | Información adicional libre                       |

**Response (202 Accepted):** igual estructura que factura pero `document_type: "05"`.

Los endpoints `GET /v1/debit-notes`, `GET /v1/debit-notes/:id`, `/xml`, `/ride`, `/resend-email` y `/void` funcionan igual.

---

## 4. Comprobantes de Retención — `POST /v1/withholdings`

Emite un comprobante de retención electrónico tipo `07`. El agente de retención emite este comprobante hacia sus proveedores.

```bash
curl -s -X POST http://localhost:8080/v1/withholdings \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: ret-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-14",
    "fiscal_period": "04/2026",
    "related_party": false,
    "subject": {
      "id_type": "04",
      "id": "0992345678001",
      "name": "Proveedor Servicios S.A.",
      "subject_type": "01",
      "email": "facturacion@proveedor.com",
      "phone": "0987654321"
    },
    "supporting_documents": [
      {
        "support_code": "01",
        "document_code": "01",
        "document_number": "001-001-000000150",
        "issue_date": "2026-04-12",
        "accounting_date": "2026-04-12",
        "authorization_number": "1204202601099234567800110010010000001501234567890",
        "payment_locality": "01",
        "regime_type": "01",
        "payment_country": "593",
        "double_taxation": "NO",
        "subject_to_retention": "SI",
        "fiscal_regime": "01",
        "total_without_tax": 500.00,
        "total_amount": 575.00,
        "taxes": [
          {
            "tax_code": "2",
            "rate_code": "4",
            "taxable_base": 500.00,
            "rate": 15.0,
            "amount": 75.00
          }
        ],
        "withholdings": [
          {
            "code": "2",
            "retention_code": "9",
            "taxable_base": 75.00,
            "retention_rate": 30.0,
            "retained_amount": 22.50
          },
          {
            "code": "1",
            "retention_code": "303",
            "taxable_base": 500.00,
            "retention_rate": 10.0,
            "retained_amount": 50.00
          }
        ],
        "payments": [
          {
            "payment_method": "20",
            "total": 575.00
          }
        ]
      }
    ],
    "additional_info": {
      "Contrato": "CT-2026-015"
    }
  }' | jq .
```

**Request — campos:**

| Campo                                   | Tipo    | Req | Descripción                                          |
| --------------------------------------- | ------- | :-: | ---------------------------------------------------- |
| `establishment`                         | string  | ✓   | Código establecimiento (3 dígitos)                   |
| `issue_point`                           | string  | ✓   | Punto de emisión (3 dígitos)                         |
| `sequence_number`                       | string  | ✓   | Secuencial (9 dígitos)                               |
| `issue_date`                            | date    | ✓   | Fecha de emisión (debe ser hoy en EC)                |
| `fiscal_period`                         | string  | ✓   | Período fiscal (`MM/YYYY`, ej: `04/2026`)            |
| `related_party`                         | boolean | ✓   | ¿Sujeto es parte relacionada?                        |
| `subject.id_type`                       | string  | ✓   | Tipo de identificación del sujeto retenido           |
| `subject.id`                            | string  | ✓   | Identificación del sujeto retenido                   |
| `subject.name`                          | string  | ✓   | Razón social del sujeto retenido                     |
| `subject.subject_type`                  | string  | ✓   | Tipo de sujeto (`01`=persona natural, `02`=sociedad) |
| `subject.email`                         | string  |     | Email                                                |
| `supporting_documents[].support_code`  | string  | ✓   | Código del sustento tributario (catálogo SRI)        |
| `supporting_documents[].document_code` | string  | ✓   | Tipo del comprobante de sustento                     |
| `supporting_documents[].document_number`| string | ✓   | Número del comprobante (`estab-pto-sec`)             |
| `supporting_documents[].issue_date`    | date    | ✓   | Fecha de emisión del sustento                        |
| `supporting_documents[].accounting_date`| date   | ✓   | Fecha de contabilización                             |
| `supporting_documents[].authorization_number`| string| ✓ | Número de autorización del sustento                 |
| `supporting_documents[].taxes`         | array   | ✓   | Impuestos del documento de sustento                  |
| `supporting_documents[].withholdings`  | array   | ✓   | Líneas de retención (código + porcentaje)            |
| `supporting_documents[].payments`      | array   | ✓   | Formas de pago del sustento                          |
| `additional_info`                       | map     |     | Información adicional libre                          |

**Response (202 Accepted):** igual estructura que factura pero `document_type: "07"`.

Los endpoints `GET /v1/withholdings`, `GET /v1/withholdings/:id`, `/xml`, `/ride`, `/resend-email` y `/void` funcionan igual.

---

## 5. Guías de Remisión — `POST /v1/waybills`

Emite una guía de remisión electrónica tipo `06`. No tiene impuestos, pagos ni totales — es un documento de transporte.

```bash
curl -s -X POST http://localhost:8080/v1/waybills \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: gr-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-14",
    "departure_address": "Bodega Principal, Av. Industrial 45, Quito",
    "carrier": {
      "id_type": "05",
      "id": "1712345678",
      "name": "Juan Pérez Transportes",
      "email": "juan.perez@transportes.com",
      "phone": "0991122334"
    },
    "transport_start_date": "2026-04-14",
    "transport_end_date": "2026-04-15",
    "license_plate": "PBY-1234",
    "addressees": [
      {
        "id": "1790012345001",
        "name": "Empresa Cliente S.A.",
        "address": "Av. Principal 123, Quito",
        "transfer_reason": "Venta",
        "destination_establishment": "001",
        "route": "Quito - Guayaquil",
        "support_document_code": "01",
        "support_document_number": "001-001-000000042",
        "support_document_auth_number": "1404202601179001234500110010010000000421234567817",
        "support_document_issue_date": "2026-04-14",
        "items": [
          {
            "main_code": "PROD-001",
            "auxiliary_code": "7861234567890",
            "description": "Servidor rack 2U",
            "quantity": 2,
            "additional_details": [
              {
                "name": "Número de serie",
                "value": "SRV-2026-001"
              }
            ]
          }
        ]
      }
    ],
    "additional_info": {
      "Temperatura": "Ambiente"
    }
  }' | jq .
```

**Request — campos:**

| Campo                                        | Tipo    | Req | Descripción                                     |
| -------------------------------------------- | ------- | :-: | ----------------------------------------------- |
| `establishment`                              | string  | ✓   | Código establecimiento (3 dígitos)              |
| `issue_point`                                | string  | ✓   | Punto de emisión (3 dígitos)                    |
| `sequence_number`                            | string  | ✓   | Secuencial (9 dígitos)                          |
| `issue_date`                                 | date    | ✓   | Fecha de emisión (debe ser hoy en EC)           |
| `departure_address`                          | string  | ✓   | Dirección de partida                            |
| `carrier.id_type`                            | string  | ✓   | Tipo de identificación del transportista        |
| `carrier.id`                                 | string  | ✓   | Identificación del transportista                |
| `carrier.name`                               | string  | ✓   | Nombre del transportista                        |
| `transport_start_date`                       | date    | ✓   | Fecha inicio del traslado                       |
| `transport_end_date`                         | date    | ✓   | Fecha fin del traslado                          |
| `license_plate`                              | string  | ✓   | Placa del vehículo (ej: `PBY-1234`)             |
| `addressees[].id`                            | string  | ✓   | Identificación del destinatario                 |
| `addressees[].name`                          | string  | ✓   | Nombre del destinatario                         |
| `addressees[].address`                       | string  | ✓   | Dirección del destinatario                      |
| `addressees[].transfer_reason`               | string  | ✓   | Razón del traslado                              |
| `addressees[].destination_establishment`     | string  |     | Establecimiento de destino                      |
| `addressees[].route`                         | string  |     | Ruta del traslado                               |
| `addressees[].support_document_code`        | string  | ✓   | Tipo del documento de sustento                  |
| `addressees[].support_document_number`      | string  | ✓   | Número del documento de sustento                |
| `addressees[].support_document_auth_number` | string  |     | Número de autorización del sustento             |
| `addressees[].support_document_issue_date`  | date    |     | Fecha del documento de sustento                 |
| `addressees[].items[].main_code`            | string  | ✓   | Código del ítem transportado                    |
| `addressees[].items[].description`          | string  | ✓   | Descripción del ítem                            |
| `addressees[].items[].quantity`             | decimal | ✓   | Cantidad                                        |
| `addressees[].items[].additional_details`  | array   |     | Detalles adicionales (`name`/`value`)           |
| `additional_info`                            | map     |     | Información adicional libre                     |

**Response (202 Accepted):** igual estructura que factura pero `document_type: "06"`. Las guías no tienen `total_amount`.

Los endpoints `GET /v1/waybills`, `GET /v1/waybills/:id`, `/xml` y `/ride` funcionan igual.

---

## 6. Liquidaciones de Compra — `POST /v1/purchase-clearances`

Emite una liquidación de compra electrónica tipo `03`, usada cuando el proveedor no está obligado a emitir comprobante.

```bash
curl -s -X POST http://localhost:8080/v1/purchase-clearances \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: lc-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-14",
    "supplier": {
      "id_type": "05",
      "id": "1712345678",
      "name": "Pedro Artesano Quito",
      "address": "Mercado Central, Puesto 45",
      "email": "pedro@correo.com",
      "phone": "0991234567"
    },
    "items": [
      {
        "main_code": "ART-001",
        "auxiliary_code": "",
        "description": "Artesanías de madera - lote 20 unidades",
        "unit_of_measure": "UNIDAD",
        "quantity": 20,
        "unit_price": 15.00,
        "discount": 0.00,
        "taxes": [
          {
            "code": "2",
            "rate_code": "0",
            "rate": 0.0
          }
        ]
      }
    ],
    "payments": [
      {
        "payment_method": "01",
        "total": 300.00,
        "term": 0,
        "time_unit": "days"
      }
    ],
    "additional_info": {
      "Evento": "Feria artesanal 2026"
    }
  }' | jq .
```

**Request — campos:**

| Campo                        | Tipo    | Req | Descripción                                   |
| ---------------------------- | ------- | :-: | --------------------------------------------- |
| `establishment`              | string  | ✓   | Código establecimiento (3 dígitos)            |
| `issue_point`                | string  | ✓   | Punto de emisión (3 dígitos)                  |
| `sequence_number`            | string  | ✓   | Secuencial (9 dígitos)                        |
| `issue_date`                 | date    | ✓   | Fecha de emisión (debe ser hoy en EC)         |
| `supplier.id_type`           | string  | ✓   | Tipo de identificación del proveedor          |
| `supplier.id`                | string  | ✓   | Identificación del proveedor                  |
| `supplier.name`              | string  | ✓   | Nombre del proveedor                          |
| `supplier.address`           | string  |     | Dirección del proveedor                       |
| `supplier.email`             | string  |     | Email                                         |
| `items[].main_code`          | string  | ✓   | Código del ítem                               |
| `items[].description`        | string  | ✓   | Descripción                                   |
| `items[].unit_of_measure`    | string  |     | Unidad de medida                              |
| `items[].quantity`           | decimal | ✓   | Cantidad                                      |
| `items[].unit_price`         | decimal | ✓   | Precio unitario                               |
| `items[].discount`           | decimal |     | Descuento                                     |
| `items[].taxes`              | array   | ✓   | Impuestos                                     |
| `payments[].payment_method`  | string  | ✓   | Forma de pago                                 |
| `payments[].total`           | decimal | ✓   | Monto                                         |
| `payments[].term`            | integer |     | Plazo                                         |
| `payments[].time_unit`       | string  |     | Unidad del plazo                              |
| `additional_info`            | map     |     | Información adicional libre                   |

**Response (202 Accepted):** igual que factura pero `document_type: "03"`.

Los endpoints `GET /v1/purchase-clearances`, `GET /v1/purchase-clearances/:id`, `/xml`, `/ride`, `/resend-email` y `/void` funcionan igual.

---

## 7. Envío de XML Raw — `POST /v1/documents/raw`

Para integradores que ya generan su propio XML conforme a la ficha técnica del SRI. Key49 valida el XSD, genera la clave de acceso, firma y gestiona el ciclo de vida completo.

```bash
curl -s -X POST http://localhost:8080/v1/documents/raw \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/xml" \
  -H "X-Document-Type: 01" \
  -H "X-Idempotency-Key: raw-$(date +%s)" \
  -d @factura.xml | jq .
```

**Headers requeridos:**

| Header              | Descripción                                                  |
| ------------------- | ------------------------------------------------------------ |
| `X-Document-Type`   | Tipo de comprobante: `01`, `03`, `04`, `05`, `06`, `07`      |
| `X-Idempotency-Key` | Clave de idempotencia (recomendado)                          |

**Estructura mínima del XML:**

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
    <!-- claveAcceso: Key49 siempre la genera, ignorará cualquier valor aquí -->
  </infoTributaria>
  <!-- ... resto del XML conforme al XSD del SRI vigente ... -->
</factura>
```

**Notas:**
- Key49 **siempre genera** la clave de acceso (49 dígitos, módulo 11).
- El XML debe conformar al XSD vigente. Key49 valida antes de procesar.
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
    "access_key": "1404202601179001234500110010010000000421234567817",
    "status": "SENT",
    "origin": "XML_RAW",
    "issue_date": "2026-04-14",
    "total_amount": 57.50,
    "created_at": "2026-04-14T20:30:00Z"
  }
}
```

### `GET /v1/documents/raw/:id`

Consultar estado de un documento enviado por canal raw. Misma estructura de respuesta que `GET /v1/invoices/:id`.

---

## 8. Exportación Masiva — `GET /v1/documents/export`

Exporta documentos en formato CSV con streaming. Útil para conciliaciones contables.

```bash
# Exportar facturas de abril 2026
curl -s "http://localhost:8080/v1/documents/export?from=2026-04-01&to=2026-04-30&document_type=01" \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -o abril-2026.csv

# Solo autorizadas
curl -s "http://localhost:8080/v1/documents/export?from=2026-04-01&to=2026-04-30&status=AUTHORIZED" \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -o autorizadas-abril.csv
```

**Query params:**

| Param           | Req | Descripción                                           |
| --------------- | :-: | ----------------------------------------------------- |
| `from`          | ✓   | Fecha desde `YYYY-MM-DD`                              |
| `to`            | ✓   | Fecha hasta `YYYY-MM-DD`                              |
| `format`        |     | Formato de salida (solo `csv` por ahora)              |
| `status`        |     | Filtrar por estado del documento                      |
| `document_type` |     | Filtrar por tipo (`01`, `03`, `04`, `05`, `06`, `07`) |
| `recipient_id`  |     | Filtrar por RUC/cédula del receptor                   |

**Respuesta:** `Content-Type: text/csv` con máximo 10.000 filas.

**Columnas CSV:**

```
access_key, document_type, establishment, issue_point, sequence_number,
recipient_id, recipient_name, total_amount, status, issue_date, authorization_date
```

---

## 9. Consulta SRI — `GET /v1/sri/authorize/:accessKey`

Consulta el estado de autorización de un comprobante directamente al SRI.

```bash
curl -s "http://localhost:8080/v1/sri/authorize/1404202601179001234500110010010000000421234567817" \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "access_key": "1404202601179001234500110010010000000421234567817",
    "sri_status": "AUTORIZADO",
    "authorization_date": "2026-04-14T20:30:15Z",
    "authorization_number": "1404202601179001234500110010010000000421234567817",
    "messages": []
  }
}
```

---

## 10. Perfil del Tenant — `GET /v1/tenant/profile`

```bash
curl -s http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
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
    "status": "active",
    "certificate": {
      "subject": "CN=EMPRESA DEMO S.A., O=Security Data",
      "serial": "1234567890ABCDEF",
      "expires_at": "2027-04-14T00:00:00Z",
      "days_remaining": 365,
      "valid": true
    },
    "plan": {
      "plan_type": "starter",
      "document_quota": 500,
      "documents_used": 127,
      "plan_starts_at": "2026-04-01T00:00:00Z",
      "plan_expires_at": "2026-05-01T00:00:00Z"
    },
    "webhook_url": "https://mi-app.com/webhooks/key49",
    "email_sender_name": "Facturación Demo",
    "reply_email": "facturacion@demo.com",
    "created_at": "2026-01-15T00:00:00Z"
  }
}
```

### `PUT /v1/tenant/profile`

Actualiza datos del tenant (no permite modificar `status`, `rate_limit_rpm` ni campos administrativos).

```bash
curl -s -X PUT http://localhost:8080/v1/tenant/profile \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -d '{
    "legal_name": "Empresa Demo S.A.",
    "trade_name": "Demo Corp Actualizado",
    "main_address": "Av. República 456, Quito",
    "webhook_url": "https://mi-app.com/webhooks/key49",
    "webhook_secret": "mi_secreto_webhook_123",
    "email_sender_name": "Facturación Demo",
    "reply_email": "facturacion@demo.com"
  }' | jq .
```

### `POST /v1/tenant/certificate`

Sube o actualiza el certificado digital `.p12`.

```bash
curl -s -X POST http://localhost:8080/v1/tenant/certificate \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -F "certificate=@/ruta/certificado.p12" \
  -F "password=contraseña_del_certificado" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "subject": "CN=EMPRESA DEMO S.A., O=Security Data",
    "serial": "1234567890ABCDEF",
    "expires_at": "2027-04-14T00:00:00Z",
    "valid": true
  }
}
```

### `GET /v1/tenant/certificate/status`

```bash
curl -s http://localhost:8080/v1/tenant/certificate/status \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "subject": "CN=EMPRESA DEMO S.A., O=Security Data",
    "serial": "1234567890ABCDEF",
    "issuer": "CN=Security Data, O=Security Data S.A.",
    "expires_at": "2027-04-14T00:00:00Z",
    "days_remaining": 365,
    "valid": true
  }
}
```

---

## 11. Gestión de API Keys

### `POST /v1/tenant/api-keys`

Crea un nuevo API key. El `raw_key` **solo se devuelve una vez** en esta respuesta.

```bash
curl -s -X POST http://localhost:8080/v1/tenant/api-keys \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Integración ERP",
    "permissions": "*",
    "expires_at": "2027-12-31T23:59:59Z"
  }' | jq .
```

**Response (201 Created):**

```json
{
  "data": {
    "api_key_id": "c1d2e3f4-a5b6-7890-cdef-123456789012",
    "key_prefix": "k49",
    "name": "Integración ERP",
    "permissions": "*",
    "expires_at": "2027-12-31T23:59:59Z",
    "status": "active",
    "raw_key": "k49_a1B2c3D4e5F6g7H8i9J0kLmN",
    "created_at": "2026-04-14T20:30:00Z"
  }
}
```

> **Guardar el `raw_key` de forma segura. No es recuperable después de esta respuesta.**

### `GET /v1/tenant/api-keys`

Lista todos los API keys del tenant (sin incluir `raw_key`).

```bash
curl -s http://localhost:8080/v1/tenant/api-keys \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

### `GET /v1/tenant/api-keys/:id`

Consulta un API key específico.

### `DELETE /v1/tenant/api-keys/:id`

Revoca un API key (irreversible).

```bash
curl -s -X DELETE http://localhost:8080/v1/tenant/api-keys/c1d2e3f4-... \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "api_key_id": "c1d2e3f4-a5b6-7890-cdef-123456789012",
    "name": "Integración ERP",
    "status": "revoked"
  }
}
```

---

## 12. Métricas del Tenant — `GET /v1/metrics/summary`

```bash
curl -s http://localhost:8080/v1/metrics/summary \
  -H "Authorization: Bearer k49_DemoKey49DevLocalTest0000" | jq .
```

**Response (200 OK):**

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
    "last_invoice_at": "2026-04-14T20:30:00Z"
  }
}
```

---

## 13. Planes y Renovaciones

### Portal: Ver plan actual

```
GET /portal/plan
```

Página HTML con plan actual, cuota usada, historial y planes disponibles.

### Portal: Solicitar renovación

```
POST /portal/plan/renew
Content-Type: multipart/form-data
```

| Campo           | Tipo   | Descripción                                          |
| --------------- | ------ | ---------------------------------------------------- |
| `requestedPlan` | string | Plan solicitado: `starter`, `business`, `enterprise` |
| `paymentProof`  | file   | Comprobante de pago (imagen o PDF)                   |
| `notes`         | string | Notas adicionales (opcional)                         |

Respuesta: redirect a `/portal/plan` con mensaje de confirmación.

---

## 14. Administración de Tenants

> Estos endpoints requieren el header `X-Admin-Token` con el token de administrador.

### `POST /v1/admin/tenants`

Registra un nuevo tenant. Crea el registro en la BD y provisiona el esquema PostgreSQL automáticamente.

```bash
curl -s -X POST http://localhost:8080/v1/admin/tenants \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "ruc": "0991234567001",
    "legal_name": "Nueva Empresa S.A.",
    "trade_name": "NuevaCorp",
    "main_address": "Guayaquil, Av. 9 de Octubre 123",
    "required_accounting": false,
    "special_taxpayer": null,
    "micro_enterprise_regime": false,
    "withholding_agent": null,
    "environment": "test",
    "schema_name": "tenant_nuevacorp"
  }' | jq .
```

**Campos del request:**

| Campo                    | Tipo    | Req | Descripción                              |
| ------------------------ | ------- | :-: | ---------------------------------------- |
| `ruc`                    | string  | ✓   | RUC del emisor (13 dígitos)              |
| `legal_name`             | string  | ✓   | Razón social                             |
| `trade_name`             | string  |     | Nombre comercial                         |
| `main_address`           | string  | ✓   | Dirección matriz                         |
| `required_accounting`    | boolean | ✓   | Obligado a llevar contabilidad           |
| `special_taxpayer`       | string  |     | Código contribuyente especial            |
| `micro_enterprise_regime`| boolean | ✓   | Régimen microempresa                     |
| `withholding_agent`      | string  |     | Resolución agente de retención           |
| `environment`            | string  | ✓   | `test` o `production`                    |
| `schema_name`            | string  | ✓   | Nombre del esquema PostgreSQL (único)    |

**Response (201 Created):**

```json
{
  "data": {
    "tenant_id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "ruc": "0991234567001",
    "legal_name": "Nueva Empresa S.A.",
    "environment": "test",
    "status": "pending",
    "schema_name": "tenant_nuevacorp",
    "created_at": "2026-04-14T20:30:00Z"
  }
}
```

### `GET /v1/admin/tenants`

Lista tenants con paginación.

```bash
# Todos los tenants
curl -s "http://localhost:8080/v1/admin/tenants" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .

# Filtrar por estado
curl -s "http://localhost:8080/v1/admin/tenants?status=active&page=1&per_page=20" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

**Query params:** `status` (active, suspended, pending, failed), `page`, `per_page`.

### `GET /v1/admin/tenants/:id`

Consulta el detalle completo de un tenant, incluyendo configuración de plan y certificado.

```bash
curl -s "http://localhost:8080/v1/admin/tenants/b2c3d4e5-..." \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

### `PUT /v1/admin/tenants/:id`

Actualiza datos de un tenant. Permite modificar campos administrativos como `status` y `rate_limit_rpm`.

```bash
curl -s -X PUT "http://localhost:8080/v1/admin/tenants/b2c3d4e5-..." \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "active",
    "rate_limit_rpm": 60,
    "environment": "production",
    "legal_name": "Nueva Empresa S.A. Actualizado"
  }' | jq .
```

**Campos del request (todos opcionales — solo se actualizan los campos incluidos):**

| Campo                    | Tipo    | Descripción                                |
| ------------------------ | ------- | ------------------------------------------ |
| `legal_name`             | string  | Razón social                               |
| `trade_name`             | string  | Nombre comercial                           |
| `main_address`           | string  | Dirección matriz                           |
| `required_accounting`    | boolean | Obligado a llevar contabilidad             |
| `special_taxpayer`       | string  | Código contribuyente especial              |
| `micro_enterprise_regime`| boolean | Régimen microempresa                       |
| `withholding_agent`      | string  | Resolución agente de retención             |
| `environment`            | string  | `test` o `production`                      |
| `webhook_url`            | string  | URL de webhook                             |
| `webhook_secret`         | string  | Secreto HMAC para firma de webhook         |
| `rate_limit_rpm`         | integer | Límite total de RPM (anula write+read)     |
| `rate_limit_write_rpm`   | integer | Límite de escritura en RPM                 |
| `rate_limit_read_rpm`    | integer | Límite de lectura en RPM                   |
| `email_sender_name`      | string  | Nombre del remitente en emails             |
| `reply_email`            | string  | Email de respuesta                         |
| `status`                 | string  | `active`, `suspended`, `pending`, `failed` |

### `PUT /v1/admin/tenants/:id/smtp`

Configura el proveedor de email de un tenant (SMTP propio o Plunk). Todos los campos son opcionales — solo se actualizan los incluidos.

```bash
curl -s -X PUT "http://localhost:8080/v1/admin/tenants/b2c3d4e5-.../smtp" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "host": "smtp.gmail.com",
    "port": 587,
    "user": "facturacion@empresa.com",
    "password": "contraseña_app",
    "from": "noreply@empresa.com",
    "email_notifications_enabled": true,
    "notify_final_consumer": false
  }' | jq .
```

**Campos del request (todos opcionales):**

| Campo                          | Tipo    | Descripción                                                           |
| ------------------------------ | ------- | --------------------------------------------------------------------- |
| `host`                         | string  | Servidor SMTP (ej: `smtp.gmail.com`)                                  |
| `port`                         | integer | Puerto SMTP (1–65535, ej: 587)                                        |
| `user`                         | string  | Usuario SMTP                                                          |
| `password`                     | string  | Contraseña SMTP (se cifra en reposo, nunca se devuelve en texto plano)|
| `from`                         | string  | Dirección remitente (ej: `noreply@empresa.com`)                       |
| `email_notifications_enabled`  | boolean | Si `false`, no se envían emails de documentos aunque SMTP esté configurado |
| `notify_final_consumer`        | boolean | Si `false`, omite el email cuando el receptor es Consumidor Final (identificación todo 9s) |

**Response (200 OK):**

```json
{
  "data": {
    "host": "smtp.gmail.com",
    "port": 587,
    "user": "facturacion@empresa.com",
    "password_configured": true,
    "from": "noreply@empresa.com",
    "enabled": true,
    "email_notifications_enabled": true,
    "notify_final_consumer": false
  }
}
```

> `enabled` es `true` cuando `host` está configurado. `password_configured` indica si hay contraseña guardada sin revelarla.

### `POST /v1/admin/tenants/:id/smtp/test`

Prueba la conectividad SMTP del tenant. Si `reply_email` está configurado, envía un email de prueba.

```bash
curl -s -X POST "http://localhost:8080/v1/admin/tenants/b2c3d4e5-.../smtp/test" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

**Response (200 OK):**

```json
{
  "data": {
    "success": true,
    "message": "Connection OK. Test email sent to facturacion@empresa.com"
  }
}
```

**Errores:** `SMTP_NOT_CONFIGURED` (422) si no hay SMTP configurado; `SMTP_CONNECTION_FAILED` (422) si no se puede conectar; `SMTP_SEND_FAILED` (422) si falla el envío del email de prueba.

---

### `POST /v1/admin/tenants/:id/certificate`

Sube el certificado `.p12` de un tenant como administrador.

```bash
curl -s -X POST "http://localhost:8080/v1/admin/tenants/b2c3d4e5-.../certificate" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -F "certificate=@/ruta/certificado.p12" \
  -F "password=contraseña_del_certificado" | jq .
```

### `GET /v1/admin/tenants/:id/certificate/status`

Consulta el estado del certificado de un tenant.

```bash
curl -s "http://localhost:8080/v1/admin/tenants/b2c3d4e5-.../certificate/status" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

### `POST /v1/admin/tenants/:id/approve`

Aprueba un tenant en estado `pending_approval`, activándolo para que pueda emitir comprobantes.

```bash
curl -s -X POST "http://localhost:8080/v1/admin/tenants/b2c3d4e5-.../approve" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"notes": "Documentación verificada"}' | jq .
```

### `POST /v1/admin/tenants/:id/reject`

Rechaza un tenant en estado `pending_approval`, suspendiéndolo. Requiere un motivo.

```bash
curl -s -X POST "http://localhost:8080/v1/admin/tenants/b2c3d4e5-.../reject" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "RUC inválido — no pasa validación módulo 11"}' | jq .
```

### Estados de un Tenant

| Estado | Descripción |
|--------|-------------|
| `pending` | Registro inicial, pendiente de verificar email |
| `pending_approval` | Email verificado, esperando aprobación del admin |
| `active` | Aprobado, puede emitir comprobantes |
| `suspended` | Suspendido (rechazado o desactivado por admin) |
| `failed` | Falló el provisioning del esquema PostgreSQL |

### Flujo de Aprobación (Portal Admin)

Además de los endpoints API, existe un panel web para gestionar aprobaciones:

```
https://key49.apx5.com/portal/admin/tenants?token={ADMIN_TOKEN}
```

Desde este panel el administrador puede ver la lista de tenants pendientes, aprobarlos con un clic, o rechazarlos especificando un motivo.

---

## 15. Administración de Renovaciones

### `GET /v1/admin/renewals`

Lista solicitudes de renovación de plan.

```bash
curl -s "http://localhost:8080/v1/admin/renewals?status=pending&page=1&per_page=20" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

**Query params:** `status` (pending, approved, rejected), `page`, `per_page`.

### `GET /v1/admin/renewals/:id`

Detalle de una solicitud, incluyendo enlace al comprobante de pago en MinIO.

```bash
curl -s "http://localhost:8080/v1/admin/renewals/{id}" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

### `POST /v1/admin/renewals/:id/approve`

Aprueba una renovación. Actualiza el plan del tenant, resetea la cuota de documentos y ajusta el rate limit.

```bash
curl -s -X POST "http://localhost:8080/v1/admin/renewals/{id}/approve" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq .
```

**Efecto:** actualiza `plan_type`, `document_quota`, `plan_expires_at`, `rate_limit_rpm` del tenant; crea registro en `plan_renewals`; envía webhook `plan.approved` al tenant.

### `POST /v1/admin/renewals/:id/reject`

Rechaza una solicitud con motivo.

```bash
curl -s -X POST "http://localhost:8080/v1/admin/renewals/{id}/reject" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Comprobante de pago ilegible"}' | jq .
```

---

## Webhooks

Key49 envía webhooks `POST` al `webhook_url` del tenant cuando ocurren eventos importantes.

### Headers

```
Content-Type: application/json
X-Key49-Signature: sha256=<hmac-sha256-del-body>
X-Key49-Event: document.authorized
X-Key49-Delivery: del_uuid
X-Key49-Timestamp: 2026-04-14T20:30:15Z
```

### Verificación de firma

El body del webhook debe verificarse antes de procesarlo. Key49 firma el body raw con HMAC-SHA256 usando el `webhook_secret` configurado en el tenant.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifica la firma HMAC-SHA256 de un webhook recibido de Key49.
 *
 * @param secret          webhook_secret configurado en el tenant
 * @param rawBody         body del request tal como llegó (sin parsear)
 * @param signatureHeader valor del header X-Key49-Signature (ej: "sha256=abc123...")
 * @return true si la firma es válida
 */
public static boolean verifyWebhookSignature(String secret, byte[] rawBody, String signatureHeader) {
    if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
        return false;
    }
    try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(rawBody);
        byte[] actual = hexToBytes(signatureHeader.substring("sha256=".length()));
        return MessageDigest.isEqual(expected, actual); // comparación en tiempo constante
    } catch (Exception e) {
        return false;
    }
}

private static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
        data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
    }
    return data;
}
```

**Ejemplo de uso en un endpoint JAX-RS:**

```java
@POST
@Path("/webhooks/key49")
@Consumes(MediaType.APPLICATION_JSON)
public Response receiveWebhook(
        byte[] body,
        @HeaderParam("X-Key49-Signature") String signature,
        @HeaderParam("X-Key49-Event") String event) {

    if (!verifyWebhookSignature(WEBHOOK_SECRET, body, signature)) {
        return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    // Procesar el evento
    switch (event) {
        case "document.authorized" -> handleAuthorized(body);
        case "document.rejected"   -> handleRejected(body);
        case "plan.approved"       -> handlePlanApproved(body);
        default                    -> Log.infof("Evento desconocido: %s", event);
    }

    return Response.ok().build(); // responder 2xx para confirmar entrega
}
```

> **Importante:** usar siempre `MessageDigest.isEqual()` para la comparación — evita ataques de timing. Nunca comparar las firmas con `equals()` o `==`.

### Eventos y payloads

**`document.authorized`**

```json
{
  "event": "document.authorized",
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "access_key": "1404202601...",
    "authorization_number": "1404202601...",
    "status": "AUTHORIZED",
    "authorization_date": "2026-04-14T20:30:15Z",
    "total_amount": 57.50,
    "recipient": {
      "id": "1790012345001",
      "name": "Empresa Cliente S.A."
    }
  },
  "timestamp": "2026-04-14T20:30:15Z"
}
```

**`document.rejected`**

```json
{
  "event": "document.rejected",
  "data": {
    "id": "d290f1ee-...",
    "document_type": "01",
    "access_key": "1404202601...",
    "status": "REJECTED",
    "sri_messages": [
      {
        "identifier": "35",
        "message": "CLAVE ACCESO REGISTRADA",
        "type": "ERROR"
      }
    ]
  },
  "timestamp": "2026-04-14T20:30:15Z"
}
```

**`document.failed`** — reintentos agotados. Misma estructura que `rejected`.

**`document.email_sent`** — email con RIDE enviado al receptor.

**`document.email_failed`** — fallo en envío de email.

**`certificate.expiring`** — certificado expira en menos de 30 días.

**`plan.approved`** — renovación de plan aprobada por el administrador.

**`plan.expired`** — plan expirado (emitido por el job de reset mensual).

**`plan.rejected`** — solicitud de renovación rechazada.

### Reintentos de webhook

- 3 intentos con backoff: 10 s → 60 s → 300 s
- Se espera HTTP 2xx como confirmación
- Si falla los 3 intentos, se marca `failed` en `webhook_deliveries`

---

## Health Checks

### `GET /q/health`

Health check combinado (readiness + liveness).

```bash
curl -s http://localhost:8080/q/health | jq .
```

### `GET /q/health/ready`

Verifica PostgreSQL, Redis, RabbitMQ y MinIO. Alerta si el certificado expira en menos de 30 días.

```bash
curl -s http://localhost:8080/q/health/ready | jq .
```

### `GET /q/health/live`

Verifica que los endpoints WSDL del SRI sean accesibles.

```bash
curl -s http://localhost:8080/q/health/live | jq .
```

---

## Portal Web

Interfaz web para gestionar documentos, plan y autoregistro. Renderizado server-side con Qute + HTMX + Pico CSS.

### Rutas del portal (autenticado)

| Ruta                           | Método | Descripción                                              |
| ------------------------------ | ------ | -------------------------------------------------------- |
| `/portal/login`                | GET    | Formulario de login                                      |
| `/portal/login`                | POST   | Procesar login, crear sesión Redis (TTL 30 min)          |
| `/portal/logout`               | GET    | Cerrar sesión                                            |
| `/portal/`                     | GET    | Dashboard con lista de documentos                        |
| `/portal/documents/:id`        | GET    | Detalle del documento: estado, timeline, descargas       |
| `/portal/documents/:id/status` | GET    | Fragmento HTML del badge de estado (HTMX polling 5 s)   |
| `/portal/plan`                 | GET    | Plan actual, planes disponibles, historial               |
| `/portal/plan/renew`           | POST   | Solicitar renovación (multipart)                         |
| `/portal/settings/profile`     | GET    | Configuración del perfil del tenant                      |
| `/portal/settings/certificate` | GET    | Gestión del certificado digital .p12                     |
| `/portal/settings/smtp`        | GET    | Configuración de email (SMTP / Plunk) y notificaciones   |
| `/portal/settings/webhook`     | GET    | Configuración de webhook                                 |
| `/portal/settings/delete`      | GET    | Solicitud de eliminación de cuenta                       |
| `/portal/forgot-password`      | GET    | Solicitud de recuperación de contraseña                  |
| `/portal/reset-password`       | GET    | Formulario de nueva contraseña (token en query param)    |

### Rutas de autoregistro (públicas)

| Ruta                          | Método | Descripción                                              |
| ----------------------------- | ------ | -------------------------------------------------------- |
| `/portal/register`            | GET    | Paso 1: datos del contribuyente                          |
| `/portal/register/verify-ruc` | POST   | Validación AJAX del RUC (HTMX)                           |
| `/portal/register/step1`      | POST   | Procesar paso 1                                          |
| `/portal/register/step2`      | GET    | Paso 2: configuración de emisión                         |
| `/portal/register/step2`      | POST   | Procesar paso 2                                          |
| `/portal/register/step3`      | GET    | Paso 3: confirmación y creación                          |
| `/portal/register/step3`      | POST   | Crear tenant y enviar email de verificación              |
| `/portal/verify`              | GET    | Verificación de email (`?token=...`)                     |

---

## OpenAPI / Swagger

```bash
# Especificación OpenAPI YAML
curl -s http://localhost:8080/q/openapi

# Swagger UI (solo en desarrollo — deshabilitado en producción)
open http://localhost:8080/q/swagger-ui
```

---

## Catálogo de Errores

### Errores generales

| HTTP | Código                        | Descripción                                                                                       |
| ---- | ----------------------------- | ------------------------------------------------------------------------------------------------- |
| 400  | `VALIDATION_ERROR`            | Uno o más campos son inválidos (ver `details`)                                                    |
| 400  | `INVALID_ESTABLISHMENT`       | `establishment` no tiene 3 dígitos                                                                |
| 400  | `INVALID_ISSUE_POINT`         | `issue_point` no tiene 3 dígitos                                                                  |
| 400  | `INVALID_SEQUENCE_NUMBER`     | `sequence_number` no tiene 9 dígitos                                                              |
| 400  | `INVALID_RECIPIENT_ID`        | RUC o cédula del receptor no pasa validación                                                      |
| 400  | `INVALID_ISSUE_DATE`          | `issue_date` no es la fecha actual en `America/Guayaquil`                                         |
| 400  | `INVALID_XML_STRUCTURE`       | XML mal formado (canal raw)                                                                       |
| 400  | `XSD_VALIDATION_FAILED`       | XML no pasa validación XSD del SRI (canal raw)                                                    |
| 400  | `DOCUMENT_TYPE_MISMATCH`      | `X-Document-Type` no coincide con `<codDoc>` del XML                                             |
| 400  | `MISSING_DOCUMENT_TYPE`       | Falta header `X-Document-Type` en canal raw                                                       |
| 401  | `UNAUTHORIZED`                | API key no proporcionado o inválido                                                               |
| 401  | `API_KEY_EXPIRED`             | API key expirado                                                                                  |
| 401  | `API_KEY_REVOKED`             | API key revocado                                                                                  |
| 403  | `TENANT_SUSPENDED`            | Tenant suspendido                                                                                 |
| 403  | `QUOTA_EXCEEDED`              | Se agotó la cuota de documentos del plan actual                                                   |
| 404  | `DOCUMENT_NOT_FOUND`          | Documento no encontrado                                                                           |
| 409  | `DUPLICATE_DOCUMENT`          | Ya existe un documento activo con el mismo tipo/establecimiento/punto/secuencial. Si estaba en `REJECTED` o `FAILED`, se recicla automáticamente y retorna 202. |
| 409  | `IDEMPOTENCY_CONFLICT`        | `X-Idempotency-Key` ya usado con un request diferente                                             |
| 409  | `INVALID_STATE_TRANSITION`    | Transición de estado no permitida                                                                 |
| 422  | `CERTIFICATE_NOT_CONFIGURED`  | Tenant no tiene certificado .p12 cargado                                                          |
| 422  | `CERTIFICATE_EXPIRED`         | Certificado .p12 expirado                                                                         |
| 422  | `UNSUPPORTED_XSD_VERSION`     | Versión de XSD no soportada                                                                       |
| 422  | `VOID_PERIOD_EXPIRED`         | Superó el día 7 del mes siguiente a la emisión para anular                                        |
| 422  | `FINAL_CONSUMER_NOT_VOIDABLE` | Facturas a consumidor final no pueden anularse                                                    |
| 429  | `RATE_LIMIT_EXCEEDED`         | Límite de requests por minuto excedido                                                            |
| 500  | `INTERNAL_ERROR`              | Error interno de Key49                                                                            |
| 502  | `SRI_UNAVAILABLE`             | No se pudo contactar al SRI                                                                       |

### Códigos de detalle de validación (`details[].code`)

| Código           | Descripción                                              |
| ---------------- | -------------------------------------------------------- |
| `REQUIRED`       | Campo obligatorio no proporcionado                       |
| `INVALID_FORMAT` | Formato incorrecto                                       |
| `INVALID_VALUE`  | Valor no reconocido en catálogo SRI                      |
| `OUT_OF_RANGE`   | Valor fuera del rango permitido                          |
| `TOO_LONG`       | Valor excede la longitud máxima                          |

### Códigos de error del SRI (referencia)

| Código | Descripción                     | Acción en Key49                      |
| ------ | ------------------------------- | ------------------------------------ |
| 35     | Documento ya registrado         | No reintentar → `REJECTED`           |
| 43     | Clave de acceso ya registrada   | Regenerar clave y reintentar         |
| 45     | Fecha fuera de rango            | No reintentar → `REJECTED`           |
| 52     | Error en estructura             | No reintentar → `REJECTED`           |
| 65     | Fecha de emisión mayor a actual | No reintentar → `REJECTED`           |
| 70     | Clave de acceso inválida        | Regenerar clave y reintentar         |

# Guía para Desarrolladores — Key49

> **Key49** es una plataforma de facturación electrónica para Ecuador (SRI).
> Esta guía te lleva desde cero hasta emitir tu primer comprobante electrónico.

---

## Tabla de Contenidos

1. [Quickstart (5 minutos)](#quickstart)
2. [Autenticación](#autenticación)
3. [Conceptos Clave](#conceptos-clave)
4. [Emitir una Factura](#emitir-una-factura)
5. [Emitir una Nota de Crédito](#emitir-una-nota-de-crédito)
6. [Emitir una Nota de Débito](#emitir-una-nota-de-débito)
7. [Emitir una Guía de Remisión](#emitir-una-guía-de-remisión)
8. [Emitir un Comprobante de Retención](#emitir-un-comprobante-de-retención)
9. [Emitir una Liquidación de Compra](#emitir-una-liquidación-de-compra)
10. [Consultar Documentos](#consultar-documentos)
11. [Descargar XML y RIDE (PDF)](#descargar-xml-y-ride)
12. [Anular un Documento](#anular-un-documento)
13. [Idempotencia](#idempotencia)
14. [Rate Limiting](#rate-limiting)
15. [Catálogo de Errores](#catálogo-de-errores)
16. [Webhooks](#webhooks)
17. [Ejemplos por Lenguaje](#ejemplos-por-lenguaje)

---

## Quickstart

### 1. Obtener credenciales

Contacta al equipo de Key49 para obtener:

- Tu **API Key** con prefijo `fec_test_` (sandbox) o `fec_live_` (producción)
- Tu **tenant** configurado con RUC y certificado .p12 del SRI

### 2. Verificar conexión

```bash
curl -s https://sandbox.key49.ec/v1/tenant/profile \
  -H "Authorization: Bearer TU_API_KEY" | jq .
```

Respuesta esperada:

```json
{
  "data": {
    "ruc": "1790012345001",
    "legal_name": "Mi Empresa S.A.",
    "environment": "test",
    "certificate_status": "VALID"
  }
}
```

### 3. Emitir tu primera factura

```bash
curl -s -X POST https://sandbox.key49.ec/v1/invoices \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: mi-primera-factura" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "'$(date +%Y-%m-%d)'",
    "recipient": {
      "id_type": "05",
      "id": "0999999999",
      "name": "Juan Pérez",
      "email": "juan@ejemplo.com"
    },
    "items": [
      {
        "main_code": "SRV-001",
        "description": "Servicio de consultoría",
        "quantity": 1,
        "unit_price": 100.00,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15 }]
      }
    ],
    "payments": [
      { "payment_method": "01", "total": 115.00, "term": 0, "time_unit": "dias" }
    ]
  }' | jq .
```

Respuesta (HTTP 202):

```json
{
  "data": {
    "id": "a1b2c3d4-...",
    "status": "CREATED",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "document_type": "01",
    "access_key": "0704202601179001234500110010010000000011234567812"
  },
  "meta": {
    "request_id": "req_abc123",
    "timestamp": "2026-04-07T10:30:00Z"
  }
}
```

### 4. Consultar el estado

```bash
curl -s https://sandbox.key49.ec/v1/invoices/a1b2c3d4-... \
  -H "Authorization: Bearer TU_API_KEY" | jq .data.status
```

El documento avanza por los estados: `CREATED` → `SIGNED` → `SENT` → `RECEIVED` → `AUTHORIZED` → `NOTIFIED`.

---

## Autenticación

Todas las peticiones a `/v1/*` requieren un API Key en el header `Authorization`:

```
Authorization: Bearer fec_test_xxxxxxxxxxxxxxxxxxxx
```

| Prefijo     | Ambiente          |
| ----------- | ----------------- |
| `fec_test_` | Sandbox (pruebas) |
| `fec_live_` | Producción        |

**Sin API Key** → HTTP 401 `UNAUTHORIZED`
**API Key expirada** → HTTP 401 `API_KEY_EXPIRED`
**Tenant suspendido** → HTTP 403 `TENANT_SUSPENDED`

---

## Conceptos Clave

### Tipos de documento

| Código | Tipo                     | Endpoint                  |
| ------ | ------------------------ | ------------------------- |
| 01     | Factura                  | `/v1/invoices`            |
| 03     | Liquidación de Compra    | `/v1/purchase-clearances` |
| 04     | Nota de Crédito          | `/v1/credit-notes`        |
| 05     | Nota de Débito           | `/v1/debit-notes`         |
| 06     | Guía de Remisión         | `/v1/waybills`            |
| 07     | Comprobante de Retención | `/v1/withholdings`        |

### Numeración (responsabilidad del cliente)

Key49 **no gestiona secuenciales**. Tu sistema debe proporcionar:

- **`establishment`**: 3 dígitos (ej: `"001"`)
- **`issue_point`**: 3 dígitos (ej: `"001"`)
- **`sequence_number`**: 9 dígitos (ej: `"000000042"`)

La combinación `(tipo_documento, establecimiento, punto_emisión, secuencial)` debe ser **única**.

### Fecha de emisión

La `issue_date` **debe ser la fecha del día actual** (zona horaria America/Guayaquil, UTC-5). Key49 rechaza fechas pasadas o futuras.

### Ciclo de vida del documento

```
CREATED → SIGNED → SENT → RECEIVED → AUTHORIZED → NOTIFIED
                                          ↓
                     Error de negocio → REJECTED (terminal)
                     Infraestructura  → RETRY → SENT (o FAILED)
                     Post-autorización → VOIDED (anulación local)
```

| Estado       | Descripción                                |
| ------------ | ------------------------------------------ |
| `CREATED`    | Documento recibido y persistido            |
| `SIGNED`     | XML generado y firmado con XAdES-BES       |
| `SENT`       | Enviado al SRI vía SOAP                    |
| `RECEIVED`   | SRI confirma recepción                     |
| `AUTHORIZED` | SRI autoriza el comprobante                |
| `NOTIFIED`   | RIDE generado y email enviado al receptor  |
| `REJECTED`   | SRI rechazó (error de negocio, terminal)   |
| `RETRY`      | Reintentando tras error de infraestructura |
| `FAILED`     | Reintentos agotados                        |
| `VOIDED`     | Anulado localmente (post-autorización)     |

---

## Emitir una Factura

```
POST /v1/invoices
```

### Campos del request

| Campo             | Tipo   | Requerido | Descripción                   |
| ----------------- | ------ | --------- | ----------------------------- |
| `establishment`   | string | Sí        | 3 dígitos: `"001"`            |
| `issue_point`     | string | Sí        | 3 dígitos: `"001"`            |
| `sequence_number` | string | Sí        | 9 dígitos: `"000000042"`      |
| `issue_date`      | string | Sí        | Fecha del día: `"2026-04-07"` |
| `recipient`       | object | Sí        | Datos del comprador           |
| `items`           | array  | Sí        | Mínimo 1 ítem                 |
| `payments`        | array  | Sí        | Mínimo 1 forma de pago        |
| `additional_info` | object | No        | Pares clave-valor libres      |

**recipient:**

| Campo     | Tipo   | Requerido | Descripción                                                          |
| --------- | ------ | --------- | -------------------------------------------------------------------- |
| `id_type` | string | Sí        | `"04"` RUC, `"05"` cédula, `"06"` pasaporte, `"07"` consumidor final |
| `id`      | string | Sí        | Número de identificación                                             |
| `name`    | string | Sí        | Razón social o nombre                                                |
| `address` | string | No        | Dirección                                                            |
| `email`   | string | No        | Email para notificación                                              |
| `phone`   | string | No        | Teléfono                                                             |

**items[]:**

| Campo             | Tipo   | Requerido | Descripción                   |
| ----------------- | ------ | --------- | ----------------------------- |
| `main_code`       | string | Sí        | Código principal del producto |
| `auxiliary_code`  | string | No        | Código auxiliar (EAN, etc.)   |
| `description`     | string | Sí        | Descripción del ítem          |
| `unit_of_measure` | string | No        | Unidad de medida              |
| `quantity`        | number | Sí        | Cantidad                      |
| `unit_price`      | number | Sí        | Precio unitario               |
| `discount`        | number | Sí        | Descuento aplicado            |
| `taxes`           | array  | Sí        | Impuestos aplicables          |

**items[].taxes[]:**

| Campo       | Tipo   | Requerido | Descripción                              |
| ----------- | ------ | --------- | ---------------------------------------- |
| `code`      | string | Sí        | `"2"` = IVA, `"3"` = ICE, `"5"` = IRBPNR |
| `rate_code` | string | Sí        | `"0"` = 0%, `"2"` = 12%, `"4"` = 15%     |
| `rate`      | number | Sí        | Porcentaje: `15`                         |

**payments[]:**

| Campo            | Tipo   | Requerido | Descripción                                                                                                                                 |
| ---------------- | ------ | --------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `payment_method` | string | Sí        | Código SRI: `"01"` efectivo, `"16"` tarjeta débito, `"17"` dinero electrónico, `"18"` tarjeta prepago, `"19"` tarjeta crédito, `"20"` otros |
| `total`          | number | Sí        | Monto del pago                                                                                                                              |
| `term`           | number | No        | Plazo (ej: `30`)                                                                                                                            |
| `time_unit`      | string | No        | `"dias"`, `"meses"`                                                                                                                         |

### Ejemplo curl

```bash
curl -s -X POST https://sandbox.key49.ec/v1/invoices \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: inv-2026-04-07-001" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "issue_date": "2026-04-07",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "address": "Av. Principal 123, Quito",
      "email": "contabilidad@cliente.com"
    },
    "items": [
      {
        "main_code": "SRV-001",
        "description": "Servicio de hosting mensual",
        "unit_of_measure": "UNIDAD",
        "quantity": 1,
        "unit_price": 50.00,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15 }]
      }
    ],
    "payments": [
      { "payment_method": "20", "total": 57.50, "term": 0, "time_unit": "dias" }
    ],
    "additional_info": {
      "Dirección": "Av. Principal 123",
      "Email": "contabilidad@cliente.com"
    }
  }'
```

---

## Emitir una Nota de Crédito

```
POST /v1/credit-notes
```

Modifica una factura emitida previamente (devolución total o parcial).

### Campos adicionales vs factura

| Campo                      | Tipo   | Requerido | Descripción                    |
| -------------------------- | ------ | --------- | ------------------------------ |
| `modified_document_code`   | string | Sí        | `"01"` = factura               |
| `modified_document_number` | string | Sí        | Ej: `"001-001-000000042"`      |
| `modified_document_date`   | string | Sí        | Fecha del documento modificado |
| `reason`                   | string | Sí        | Motivo de la nota de crédito   |

### Ejemplo curl

```bash
curl -s -X POST https://sandbox.key49.ec/v1/credit-notes \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: cn-2026-04-07-001" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-07",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "email": "contabilidad@cliente.com"
    },
    "modified_document_code": "01",
    "modified_document_number": "001-001-000000042",
    "modified_document_date": "2026-04-07",
    "reason": "Devolución parcial de mercadería",
    "items": [
      {
        "internal_code": "PROD-001",
        "description": "Producto devuelto",
        "quantity": 1,
        "unit_price": 50.00,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15 }]
      }
    ],
    "additional_info": {
      "Motivo": "Devolución por defecto de fábrica"
    }
  }'
```

---

## Emitir una Nota de Débito

```
POST /v1/debit-notes
```

Incrementa el valor de una factura emitida (cargos adicionales, intereses, etc.).

### Campos adicionales vs factura

| Campo                      | Tipo   | Requerido | Descripción                    |
| -------------------------- | ------ | --------- | ------------------------------ |
| `modified_document_code`   | string | Sí        | `"01"` = factura               |
| `modified_document_number` | string | Sí        | Ej: `"001-001-000000042"`      |
| `modified_document_date`   | string | Sí        | Fecha del documento modificado |
| `reasons`                  | array  | Sí        | Motivos del débito             |
| `taxes`                    | array  | Sí        | Impuestos sobre los motivos    |

**reasons[]:**

| Campo         | Tipo   | Requerido | Descripción           |
| ------------- | ------ | --------- | --------------------- |
| `description` | string | Sí        | Descripción del cargo |
| `amount`      | number | Sí        | Valor del cargo       |

### Ejemplo curl

```bash
curl -s -X POST https://sandbox.key49.ec/v1/debit-notes \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: dn-2026-04-07-001" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-07",
    "recipient": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Empresa Cliente S.A.",
      "email": "contabilidad@cliente.com"
    },
    "modified_document_code": "01",
    "modified_document_number": "001-001-000000042",
    "modified_document_date": "2026-04-07",
    "reasons": [
      { "description": "Intereses por mora", "amount": 25.00 }
    ],
    "taxes": [
      { "code": "2", "rate_code": "4", "rate": 15 }
    ],
    "payments": [
      { "payment_method": "01", "total": 28.75, "term": 0, "time_unit": "dias" }
    ]
  }'
```

---

## Emitir una Guía de Remisión

```
POST /v1/waybills
```

Ampara el transporte de mercadería entre establecimientos o a clientes.

### Campos del request

| Campo                  | Tipo   | Requerido | Descripción                           |
| ---------------------- | ------ | --------- | ------------------------------------- |
| `establishment`        | string | Sí        | 3 dígitos                             |
| `issue_point`          | string | Sí        | 3 dígitos                             |
| `sequence_number`      | string | Sí        | 9 dígitos                             |
| `issue_date`           | string | Sí        | Fecha del día                         |
| `departure_address`    | string | Sí        | Dirección de partida                  |
| `carrier`              | object | Sí        | Datos del transportista               |
| `transport_start_date` | string | Sí        | Fecha inicio del transporte           |
| `transport_end_date`   | string | Sí        | Fecha fin del transporte              |
| `license_plate`        | string | Sí        | Placa del vehículo (ej: `"PBA-1234"`) |
| `addressees`           | array  | Sí        | Destinatarios (mínimo 1)              |

**carrier:**

| Campo     | Tipo   | Requerido | Descripción                    |
| --------- | ------ | --------- | ------------------------------ |
| `id_type` | string | Sí        | Tipo de identificación         |
| `id`      | string | Sí        | RUC o cédula del transportista |
| `name`    | string | Sí        | Nombre o razón social          |
| `email`   | string | No        | Email                          |
| `phone`   | string | No        | Teléfono                       |

**addressees[]:**

| Campo                       | Tipo   | Requerido | Descripción                              |
| --------------------------- | ------ | --------- | ---------------------------------------- |
| `id`                        | string | Sí        | Identificación del destinatario          |
| `name`                      | string | Sí        | Nombre del destinatario                  |
| `address`                   | string | Sí        | Dirección de destino                     |
| `transfer_reason`           | string | Sí        | Motivo del traslado                      |
| `destination_establishment` | string | No        | Código de establecimiento destino        |
| `route`                     | string | No        | Ruta del transporte                      |
| `support_document_code`     | string | Sí        | `"01"` factura, `"03"` liquidación, etc. |
| `support_document_number`   | string | Sí        | Ej: `"001-001-000000042"`                |
| `items`                     | array  | Sí        | Ítems transportados                      |

**addressees[].items[]:**

| Campo         | Tipo   | Requerido | Descripción         |
| ------------- | ------ | --------- | ------------------- |
| `main_code`   | string | Sí        | Código del producto |
| `description` | string | Sí        | Descripción         |
| `quantity`    | number | Sí        | Cantidad            |

### Ejemplo curl

```bash
curl -s -X POST https://sandbox.key49.ec/v1/waybills \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: wb-2026-04-07-001" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-07",
    "departure_address": "Av. 10 de Agosto N50-100, Quito",
    "carrier": {
      "id_type": "04",
      "id": "1790016919001",
      "name": "TransLogística S.A."
    },
    "transport_start_date": "2026-04-07",
    "transport_end_date": "2026-04-07",
    "license_plate": "PBA-1234",
    "addressees": [
      {
        "id": "1790012345001",
        "name": "Empresa S.A.",
        "address": "Av. Amazonas N37-29, Guayaquil",
        "transfer_reason": "Venta de mercadería",
        "support_document_code": "01",
        "support_document_number": "001-001-000000042",
        "items": [
          {
            "main_code": "PROD-001",
            "description": "Mercadería varia",
            "quantity": 10
          }
        ]
      }
    ]
  }'
```

---

## Emitir un Comprobante de Retención

```
POST /v1/withholdings
```

Emitido por el agente de retención para declarar retenciones de impuestos.

### Campos del request

| Campo                  | Tipo    | Requerido | Descripción                       |
| ---------------------- | ------- | --------- | --------------------------------- |
| `establishment`        | string  | Sí        | 3 dígitos                         |
| `issue_point`          | string  | Sí        | 3 dígitos                         |
| `sequence_number`      | string  | Sí        | 9 dígitos                         |
| `issue_date`           | string  | Sí        | Fecha del día                     |
| `subject`              | object  | Sí        | Datos del sujeto retenido         |
| `fiscal_period`        | string  | Sí        | Período fiscal: `"04/2026"`       |
| `related_party`        | boolean | No        | `true` si es parte relacionada    |
| `supporting_documents` | array   | Sí        | Documentos de sustento (mínimo 1) |

**subject:**

| Campo          | Tipo   | Requerido | Descripción                    |
| -------------- | ------ | --------- | ------------------------------ |
| `id_type`      | string | Sí        | Tipo de identificación         |
| `id`           | string | Sí        | RUC o cédula del retenido      |
| `name`         | string | Sí        | Nombre o razón social          |
| `subject_type` | string | No        | Tipo de sujeto: `"01"`, `"02"` |
| `email`        | string | No        | Email                          |

**supporting_documents[]:**

| Campo               | Tipo   | Requerido | Descripción                  |
| ------------------- | ------ | --------- | ---------------------------- |
| `support_code`      | string | Sí        | Código tipo sustento ATS     |
| `document_code`     | string | Sí        | `"01"` factura, etc.         |
| `document_number`   | string | Sí        | Ej: `"001-001-000000042"`    |
| `issue_date`        | string | Sí        | Fecha del documento sustento |
| `payment_locality`  | string | No        | Código de localidad          |
| `total_without_tax` | number | Sí        | Subtotal sin impuestos       |
| `total_amount`      | number | Sí        | Total con impuestos          |
| `taxes`             | array  | Sí        | Impuestos del documento      |
| `withholdings`      | array  | Sí        | Retenciones aplicadas        |
| `payments`          | array  | Sí        | Formas de pago               |

**withholdings[]:**

| Campo             | Tipo   | Requerido | Descripción                       |
| ----------------- | ------ | --------- | --------------------------------- |
| `code`            | string | Sí        | `"1"` renta, `"2"` IVA, `"6"` ISD |
| `retention_code`  | string | Sí        | Código de retención (ej: `"312"`) |
| `taxable_base`    | number | Sí        | Base imponible                    |
| `retention_rate`  | number | Sí        | Porcentaje de retención           |
| `retained_amount` | number | Sí        | Monto retenido                    |

### Ejemplo curl

```bash
curl -s -X POST https://sandbox.key49.ec/v1/withholdings \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: wh-2026-04-07-001" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-07",
    "subject": {
      "id_type": "04",
      "id": "1790012345001",
      "name": "Proveedor Nacional S.A.",
      "email": "proveedor@empresa.com"
    },
    "fiscal_period": "04/2026",
    "supporting_documents": [
      {
        "support_code": "01",
        "document_code": "01",
        "document_number": "001-001-000000100",
        "issue_date": "2026-04-07",
        "payment_locality": "01",
        "total_without_tax": 1000.00,
        "total_amount": 1150.00,
        "taxes": [
          {
            "tax_code": "2",
            "rate_code": "4",
            "taxable_base": 1000.00,
            "rate": 15.00,
            "amount": 150.00
          }
        ],
        "withholdings": [
          {
            "code": "1",
            "retention_code": "312",
            "taxable_base": 1000.00,
            "retention_rate": 1.00,
            "retained_amount": 10.00
          }
        ],
        "payments": [
          { "payment_method": "01", "total": 1150.00 }
        ]
      }
    ]
  }'
```

---

## Emitir una Liquidación de Compra

```
POST /v1/purchase-clearances
```

Emitida cuando se adquiere bienes o servicios a personas no obligadas a llevar contabilidad.

### Diferencia con factura

En lugar de `recipient`, se usa **`supplier`** (datos del proveedor informal).

| Campo      | Tipo   | Requerido | Descripción         |
| ---------- | ------ | --------- | ------------------- |
| `supplier` | object | Sí        | Datos del proveedor |

**supplier:**

| Campo     | Tipo   | Requerido | Descripción                                                                               |
| --------- | ------ | --------- | ----------------------------------------------------------------------------------------- |
| `id_type` | string | Sí        | `"04"` RUC, `"05"` cédula, `"06"` pasaporte, `"07"` consumidor final, `"08"` id. exterior |
| `id`      | string | Sí        | Número de identificación                                                                  |
| `name`    | string | Sí        | Nombre del proveedor                                                                      |
| `address` | string | No        | Dirección                                                                                 |
| `email`   | string | No        | Email                                                                                     |
| `phone`   | string | No        | Teléfono                                                                                  |

Los campos `items` y `payments` son idénticos a los de factura.

### Ejemplo curl

```bash
curl -s -X POST https://sandbox.key49.ec/v1/purchase-clearances \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: pc-2026-04-07-001" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000001",
    "issue_date": "2026-04-07",
    "supplier": {
      "id_type": "05",
      "id": "0923456789",
      "name": "María García López",
      "address": "Guayaquil, Ecuador",
      "email": "maria@ejemplo.com"
    },
    "items": [
      {
        "main_code": "AGR-001",
        "description": "Productos agrícolas",
        "unit_of_measure": "kilogramo",
        "quantity": 100,
        "unit_price": 2.50,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "0", "rate": 0 }]
      }
    ],
    "payments": [
      { "payment_method": "01", "total": 250.00, "term": 0, "time_unit": "dias" }
    ]
  }'
```

---

## Consultar Documentos

### Por ID

```bash
# Factura
curl -s https://sandbox.key49.ec/v1/invoices/{id} \
  -H "Authorization: Bearer TU_API_KEY"

# Nota de crédito
curl -s https://sandbox.key49.ec/v1/credit-notes/{id} \
  -H "Authorization: Bearer TU_API_KEY"

# Retención
curl -s https://sandbox.key49.ec/v1/withholdings/{id} \
  -H "Authorization: Bearer TU_API_KEY"
```

### Listar con filtros

```bash
curl -s "https://sandbox.key49.ec/v1/invoices?status=AUTHORIZED&date_from=2026-04-01&date_to=2026-04-07&page=1&per_page=10" \
  -H "Authorization: Bearer TU_API_KEY"
```

**Filtros disponibles:**

| Parámetro      | Tipo   | Descripción                                                            |
| -------------- | ------ | ---------------------------------------------------------------------- |
| `status`       | string | Filtrar por estado                                                     |
| `date_from`    | string | Fecha desde (YYYY-MM-DD)                                               |
| `date_to`      | string | Fecha hasta (YYYY-MM-DD)                                               |
| `recipient_id` | string | Identificación del receptor                                            |
| `access_key`   | string | Clave de acceso (49 dígitos)                                           |
| `page`         | int    | Página (default: 1)                                                    |
| `per_page`     | int    | Resultados por página (default: 20, max: 100)                          |
| `sort`         | string | Ordenamiento: `created_at`, `-created_at`, `issue_date`, `-issue_date` |

### Respuesta paginada

```json
{
  "data": [ { "id": "...", "status": "AUTHORIZED", ... } ],
  "meta": {
    "total": 150,
    "page": 1,
    "per_page": 20,
    "total_pages": 8
  }
}
```

---

## Descargar XML y RIDE

### XML autorizado

```bash
curl -s -OJ https://sandbox.key49.ec/v1/invoices/{id}/xml \
  -H "Authorization: Bearer TU_API_KEY"
```

Retorna el XML autorizado por el SRI (`application/xml`). Solo disponible para documentos en estado `AUTHORIZED` o posterior.

### RIDE (PDF)

```bash
curl -s -OJ https://sandbox.key49.ec/v1/invoices/{id}/ride \
  -H "Authorization: Bearer TU_API_KEY"
```

Retorna el RIDE en formato PDF (`application/pdf`). Solo disponible cuando el RIDE ha sido generado.

### Reenviar email

```bash
curl -s -X POST https://sandbox.key49.ec/v1/invoices/{id}/resend-email \
  -H "Authorization: Bearer TU_API_KEY"
```

Reenvía el email con XML y RIDE adjuntos. Solo funciona para documentos `AUTHORIZED` o `NOTIFIED`.

---

## Anular un Documento

```
POST /v1/invoices/{id}/void
POST /v1/credit-notes/{id}/void
POST /v1/debit-notes/{id}/void
POST /v1/waybills/{id}/void
POST /v1/withholdings/{id}/void
POST /v1/purchase-clearances/{id}/void
```

> **Importante**: Key49 solo registra la anulación **localmente**. La anulación ante el SRI la realiza el contribuyente directamente en el portal del SRI.

```bash
curl -s -X POST https://sandbox.key49.ec/v1/invoices/{id}/void \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{ "reason": "Error en datos del cliente" }'
```

**Requisitos:**

- El documento debe estar en estado `AUTHORIZED` o `NOTIFIED`
- El campo `reason` es obligatorio

---

## Idempotencia

Todas las operaciones de creación soportan el header `X-Idempotency-Key`:

```
X-Idempotency-Key: mi-clave-unica-12345
```

**Comportamiento:**

| Escenario                          | Resultado                                      |
| ---------------------------------- | ---------------------------------------------- |
| Primera petición con el key        | Crea el documento normalmente                  |
| Misma petición con el mismo key    | Retorna el resultado original (sin reprocesar) |
| Petición distinta con el mismo key | HTTP 409 `IDEMPOTENCY_CONFLICT`                |

**Recomendación**: usa UUIDs o timestamps combinados con el tipo de documento como claves de idempotencia.

---

## Rate Limiting

- **Límite default**: 100 requests/minuto por API key (configurable por tenant)
- **Headers de respuesta**:

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1712234567
```

Al exceder el límite: HTTP 429 con header `Retry-After`:

```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Try again in 45 seconds."
  }
}
```

---

## Catálogo de Errores

### Errores de validación (400)

| Código                    | Descripción                              |
| ------------------------- | ---------------------------------------- |
| `VALIDATION_ERROR`        | Campos inválidos (ver `error.details[]`) |
| `INVALID_ESTABLISHMENT`   | Establecimiento no tiene 3 dígitos       |
| `INVALID_ISSUE_POINT`     | Punto de emisión no tiene 3 dígitos      |
| `INVALID_SEQUENCE_NUMBER` | Secuencial no tiene 9 dígitos            |
| `INVALID_RECIPIENT_ID`    | RUC/cédula en formato inválido           |
| `INVALID_ISSUE_DATE`      | Fecha de emisión no es hoy (UTC-5)       |

### Errores de autenticación (401/403)

| Código             | Descripción                 |
| ------------------ | --------------------------- |
| `UNAUTHORIZED`     | API key faltante o inválida |
| `API_KEY_EXPIRED`  | API key expirada            |
| `API_KEY_REVOKED`  | API key revocada            |
| `TENANT_SUSPENDED` | Tenant suspendido           |

### Errores de conflicto (409)

| Código                     | Descripción                                                                                                                                           |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DUPLICATE_DOCUMENT`       | Documento activo/completado con mismo tipo/establecimiento/punto/secuencial. Documentos en REJECTED/FAILED se reciclan automáticamente (retorna 202). |
| `IDEMPOTENCY_CONFLICT`     | Key de idempotencia usada con body distinto                                                                                                           |
| `INVALID_STATE_TRANSITION` | Transición de estado no permitida                                                                                                                     |

### Errores de procesamiento (422)

| Código                       | Descripción                     |
| ---------------------------- | ------------------------------- |
| `CERTIFICATE_NOT_CONFIGURED` | Tenant sin certificado .p12     |
| `CERTIFICATE_EXPIRED`        | Certificado del tenant expirado |

### Errores de límite (429)

| Código                | Descripción                           |
| --------------------- | ------------------------------------- |
| `RATE_LIMIT_EXCEEDED` | Excedido el límite de requests/minuto |

### Errores de servidor (500/502)

| Código            | Descripción                                      |
| ----------------- | ------------------------------------------------ |
| `INTERNAL_ERROR`  | Error interno del servidor                       |
| `SRI_UNAVAILABLE` | SRI no disponible (timeout o conexión rechazada) |

### Formato de error

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "El campo recipient.id es requerido",
    "details": [
      {
        "field": "recipient.id",
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

## Webhooks

Key49 puede notificar eventos a tu sistema vía webhooks HTTP POST.

### Configuración

```bash
curl -s -X PUT https://sandbox.key49.ec/v1/tenant/profile \
  -H "Authorization: Bearer TU_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "webhook_url": "https://tu-sistema.com/webhooks/key49",
    "webhook_secret": "tu-secreto-para-verificar-firma"
  }'
```

### Payload del webhook

```json
{
  "event": "document.authorized",
  "document_id": "a1b2c3d4-...",
  "document_type": "01",
  "access_key": "0704202601179001234500110010010000000011234567812",
  "status": "AUTHORIZED",
  "timestamp": "2026-04-07T10:35:00Z"
}
```

### Verificación de firma

El header `X-Key49-Signature` contiene un HMAC-SHA256 del body con tu `webhook_secret`:

```python
import hmac, hashlib

expected = hmac.new(
    webhook_secret.encode(),
    request_body,
    hashlib.sha256
).hexdigest()

if not hmac.compare_digest(expected, request.headers["X-Key49-Signature"]):
    return 403
```

---

## Ejemplos por Lenguaje

### Python

```python
import requests
from datetime import date

API_KEY = "fec_test_TuApiKeyAqui"
BASE_URL = "https://sandbox.key49.ec/v1"

headers = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
    "X-Idempotency-Key": f"inv-{date.today()}-001"
}

# Emitir factura
invoice = {
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "issue_date": str(date.today()),
    "recipient": {
        "id_type": "04",
        "id": "1790012345001",
        "name": "Empresa Cliente S.A.",
        "email": "contabilidad@cliente.com"
    },
    "items": [{
        "main_code": "SRV-001",
        "description": "Servicio de consultoría",
        "quantity": 1,
        "unit_price": 100.00,
        "discount": 0.00,
        "taxes": [{"code": "2", "rate_code": "4", "rate": 15}]
    }],
    "payments": [{
        "payment_method": "01",
        "total": 115.00,
        "term": 0,
        "time_unit": "dias"
    }]
}

resp = requests.post(f"{BASE_URL}/invoices", json=invoice, headers=headers)
print(resp.status_code)  # 202
data = resp.json()["data"]
print(f"ID: {data['id']}, Estado: {data['status']}")

# Consultar estado
resp = requests.get(f"{BASE_URL}/invoices/{data['id']}", headers=headers)
print(resp.json()["data"]["status"])

# Descargar XML
resp = requests.get(f"{BASE_URL}/invoices/{data['id']}/xml", headers=headers)
with open("factura.xml", "wb") as f:
    f.write(resp.content)

# Descargar RIDE (PDF)
resp = requests.get(f"{BASE_URL}/invoices/{data['id']}/ride", headers=headers)
with open("factura.pdf", "wb") as f:
    f.write(resp.content)
```

### Node.js

```javascript
const API_KEY = "fec_test_TuApiKeyAqui";
const BASE_URL = "https://sandbox.key49.ec/v1";

const headers = {
  Authorization: `Bearer ${API_KEY}`,
  "Content-Type": "application/json",
  "X-Idempotency-Key": `inv-${new Date().toISOString().split("T")[0]}-001`,
};

// Emitir factura
const invoice = {
  establishment: "001",
  issue_point: "001",
  sequence_number: "000000042",
  issue_date: new Date().toISOString().split("T")[0],
  recipient: {
    id_type: "04",
    id: "1790012345001",
    name: "Empresa Cliente S.A.",
    email: "contabilidad@cliente.com",
  },
  items: [
    {
      main_code: "SRV-001",
      description: "Servicio de consultoría",
      quantity: 1,
      unit_price: 100.0,
      discount: 0.0,
      taxes: [{ code: "2", rate_code: "4", rate: 15 }],
    },
  ],
  payments: [
    {
      payment_method: "01",
      total: 115.0,
      term: 0,
      time_unit: "dias",
    },
  ],
};

const resp = await fetch(`${BASE_URL}/invoices`, {
  method: "POST",
  headers,
  body: JSON.stringify(invoice),
});

const { data } = await resp.json();
console.log(`ID: ${data.id}, Estado: ${data.status}`);

// Consultar estado
const detail = await fetch(`${BASE_URL}/invoices/${data.id}`, { headers });
const doc = await detail.json();
console.log(doc.data.status);

// Descargar XML
const xml = await fetch(`${BASE_URL}/invoices/${data.id}/xml`, { headers });
const fs = await import("fs");
fs.writeFileSync("factura.xml", Buffer.from(await xml.arrayBuffer()));
```

### Java (HttpClient)

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.LocalDate;

var client = HttpClient.newHttpClient();
var apiKey = "fec_test_TuApiKeyAqui";
var baseUrl = "https://sandbox.key49.ec/v1";

// Emitir factura
var body = """
    {
      "establishment": "001",
      "issue_point": "001",
      "sequence_number": "000000042",
      "issue_date": "%s",
      "recipient": {
        "id_type": "04",
        "id": "1790012345001",
        "name": "Empresa Cliente S.A.",
        "email": "contabilidad@cliente.com"
      },
      "items": [{
        "main_code": "SRV-001",
        "description": "Servicio de consultoría",
        "quantity": 1,
        "unit_price": 100.00,
        "discount": 0.00,
        "taxes": [{"code": "2", "rate_code": "4", "rate": 15}]
      }],
      "payments": [{
        "payment_method": "01",
        "total": 115.00,
        "term": 0,
        "time_unit": "dias"
      }]
    }
    """.formatted(LocalDate.now());

var request = HttpRequest.newBuilder()
    .uri(URI.create(baseUrl + "/invoices"))
    .header("Authorization", "Bearer " + apiKey)
    .header("Content-Type", "application/json")
    .header("X-Idempotency-Key", "inv-" + LocalDate.now() + "-001")
    .POST(BodyPublishers.ofString(body))
    .build();

var response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println("Status: " + response.statusCode()); // 202
System.out.println(response.body());

// Consultar estado
var getRequest = HttpRequest.newBuilder()
    .uri(URI.create(baseUrl + "/invoices/" + documentId))
    .header("Authorization", "Bearer " + apiKey)
    .GET()
    .build();

var getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
System.out.println(getResponse.body());
```

---

## Soporte

- **Email**: soporte@key49.ec
- **Documentación técnica**: `docs/API.md` (contrato completo)
- **Estado del servicio**: `/q/health`

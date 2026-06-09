# Key49 — Guía de Integración para Facturación Electrónica

> Instrucciones para un agente Pi de otro proyecto que necesita enviar facturas de venta a Key49.

---

## 1. Credenciales

Necesitas un **API Key** de Key49. Tiene el prefijo `k49_` seguido de 24 caracteres:

```
Authorization: Bearer k49_xxxxxxxxxxxxxxxxxxxxxxxx
```

El ambiente del SRI (pruebas o producción) lo determina la configuración del tenant, no el API key.

---

## 2. Base URL

```
Sandbox:     https://key49.apx5.com/v1
```

> Cambiar a la URL de producción cuando corresponda.

---

## 3. Enviar una Factura — `POST /v1/invoices`

> ⚠️ **La `access_key` es opcional.** Si el cliente la envía (49 dígitos con módulo 11 válido), Key49 la valida contra los datos del request y la usa tal cual. Si no la envía o es inválida, Key49 la genera automáticamente.

### Request

```bash
curl -s -X POST https://key49.apx5.com/v1/invoices \
  -H "Authorization: Bearer k49_xxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: factura-unica-$(date +%s)" \
  -d '{
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "issue_date": "2026-06-09",
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
      "Orden de compra": "OC-2026-0042"
    }
  }'
```

### Campos del request

| Campo | Tipo | Requerido | Descripción |
|-------|------|:--------:|-------------|
| `establishment` | string `\d{3}` | ✓ | Código establecimiento (3 dígitos) |
| `issue_point` | string `\d{3}` | ✓ | Punto de emisión (3 dígitos) |
| `sequence_number` | string `\d{9}` | ✓ | Secuencial (9 dígitos, rellena con ceros a la izq) |
| `issue_date` | `YYYY-MM-DD` | ✓ | Fecha de emisión (debe ser HOY en zona Ecuador) |
| `recipient.id_type` | string | ✓ | `04`=RUC, `05`=Cédula, `06`=Pasaporte, `07`=Consumidor Final |
| `recipient.id` | string | ✓ | RUC (13 dígitos) o cédula (10 dígitos) |
| `recipient.name` | string | ✓ | Razón social o nombre |
| `recipient.address` | string | | Dirección |
| `recipient.email` | string | | Email para envío del PDF |
| `recipient.phone` | string | | Teléfono |
| `items[].main_code` | string | ✓ | Código del producto/servicio |
| `items[].auxiliary_code` | string | | Código auxiliar (ej: código de barras) |
| `items[].description` | string | ✓ | Descripción |
| `items[].unit_of_measure` | string | | Unidad (`UNIDAD`, `KG`, etc.) |
| `items[].quantity` | decimal | ✓ | Cantidad |
| `items[].unit_price` | decimal | ✓ | Precio unitario sin IVA |
| `items[].discount` | decimal | | Descuento en valor absoluto |
| `items[].taxes[].code` | string | ✓ | `2`=IVA, `3`=ICE, `5`=IRBPNR |
| `items[].taxes[].rate_code` | string | ✓ | `0`=0%, `2`=12%, `4`=15%, `6`=No objeto, `7`=Exento |
| `items[].taxes[].rate` | decimal | ✓ | Porcentaje (ej: `15.0`) |
| `payments[].payment_method` | string | ✓ | `01`=Efectivo, `16`=Débito, `19`=Crédito, `20`=Transferencia, `21`=Cheque |
| `payments[].total` | decimal | ✓ | Monto total de este pago (con IVA) |
| `payments[].term` | integer | | Plazo (0 = contado) |
| `payments[].time_unit` | string | | `days` o `months` |
| `additional_info` | object | | Info extra (clave-valor) |

### Catálogos rápidos

**Tipos de identificación (`id_type`):**
| Código | Tipo | Longitud |
|--------|------|----------|
| `04` | RUC | 13 |
| `05` | Cédula | 10 |
| `06` | Pasaporte | 3-20 |
| `07` | Consumidor Final | 13 (todo nueves: `9999999999999`) |

**Tarifas IVA (`rate_code`):**
| Código | Tarifa |
|--------|--------|
| `0` | 0% |
| `2` | 12% |
| `4` | 15% |

---

## 4. Response (202 Accepted)

```json
{
  "data": {
    "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "document_type": "01",
    "establishment": "001",
    "issue_point": "001",
    "sequence_number": "000000042",
    "access_key": null,
    "status": "CREATED",
    "issue_date": "2026-06-09",
    "total_amount": 57.50,
    "recipient": {
      "id": "1790012345001",
      "name": "Empresa Cliente S.A."
    },
    "created_at": "2026-06-09T15:30:00Z"
  },
  "meta": {
    "request_id": "req_abc123def456",
    "timestamp": "2026-06-09T15:30:00Z"
  }
}
```

> ⚠️ **Si el cliente envió `access_key` en el request, Key49 la devuelve igual en la respuesta.** Si no la envió, viene `null` hasta que el `SignConsumer` la genere (~2-5 seg).

**Guarda el `id`** para consultar el estado después.

---

## 5. Consultar Estado — `GET /v1/invoices/:id`

```bash
curl -s https://key49.apx5.com/v1/invoices/d290f1ee-6c54-4b01-90e6-d701748f0851 \
  -H "Authorization: Bearer k49_xxxxxxxxxxxxxxxxxxxxxxxx"
```

### Estados del documento

| Estado | Significado |
|--------|-------------|
| `CREATED` | Creado, entrando a la cola de firma |
| `SIGNED` | XML firmado con XAdES-BES |
| `SENT` | Enviado al SRI |
| `RECEIVED` | SRI confirmó recepción |
| `AUTHORIZED` | ✅ **Autorizado por el SRI** — ¡listo! |
| `NOTIFIED` | Email con PDF + XML enviado al receptor |
| `REJECTED` | ❌ Rechazado por el SRI |
| `FAILED` | ❌ Reintentos agotados |
| `VOIDED` | Anulado localmente |

### Polling para autorización

El flujo normal tarda **5-30 segundos**. La `access_key` aparece cuando el `status` llega a `SIGNED`.

```
Pseudocódigo:
1. POST /v1/invoices → obtener id
2. Esperar 2 segundos
3. GET /v1/invoices/:id → verificar status
4. Si CREATED/SIGNED/SENT/RECEIVED → volver al paso 2 (máx 10 intentos)
5. Si AUTHORIZED → éxito, descargar PDF+XML
6. Si REJECTED/FAILED → error, revisar sri_messages
```

---

## 6. Descargar XML y PDF (RIDE)

Solo disponible cuando el estado es `AUTHORIZED` o `NOTIFIED`:

```bash
# XML autorizado (firmado XAdES-BES)
curl -s https://key49.apx5.com/v1/invoices/d290f1ee.../xml \
  -H "Authorization: Bearer k49_xxxxxxxxxxxxxxxxxxxxxxxx" \
  -o factura.xml

# RIDE (PDF)
curl -s https://key49.apx5.com/v1/invoices/d290f1ee.../ride \
  -H "Authorization: Bearer k49_xxxxxxxxxxxxxxxxxxxxxxxx" \
  -o factura.pdf
```

---

## 7. Manejo de Errores

### Error de validación (400)

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
  }
}
```

### Rate limit (429)

```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Demasiadas peticiones. Reintente en 30s."
  }
}
```

Responde al header `Retry-After` con el tiempo de espera en segundos.

### Idempotencia (409)

Si reenvías el mismo `X-Idempotency-Key` con un body diferente:

```json
{
  "error": {
    "code": "IDEMPOTENCY_CONFLICT",
    "message": "Mismo idempotency key con body diferente"
  }
}
```

### Errores del SRI (en el status del documento)

Cuando `status = REJECTED`, revisa `sri_messages` en el GET del documento:

```json
{
  "data": {
    "status": "REJECTED",
    "sri_messages": [
      {
        "code": "43",
        "message": "CLAVE DE ACCESO REGISTRADA"
      }
    ]
  }
}
```

**Códigos SRI que no se reintentan:** 35 (ya registrado), 45 (fecha fuera de rango), 52 (estructura inválida), 65 (fecha futura).

---

## 8. Requisitos Importantes

### ⚠️ Fecha de emisión
`issue_date` debe ser la fecha **actual** en zona horaria `America/Guayaquil` (UTC-5). Key49 rechaza facturas con fecha diferente a hoy.

### ⚠️ Secuenciales
El `sequence_number` lo gestiona tu sistema. Key49 no asigna secuenciales. El formato es 9 dígitos con ceros a la izquierda: `000000042`.

### ⚠️ Unicidad
La combinación `establishment + issue_point + sequence_number` debe ser única. Si envías un duplicado, el SRI lo rechazará con código 35.

### ⚠️ Consumidor Final
Si `id_type = "07"`, el `id` debe ser `9999999999999` (13 dígitos, todo nueves). Estas facturas no pueden anularse.

---

## 9. Ejemplo Completo en JavaScript/Node.js

```javascript
const API_KEY = 'k49_xxxxxxxxxxxxxxxxxxxxxxxx';
const BASE_URL = 'https://key49.apx5.com/v1';

async function emitirFactura(factura) {
  const res = await fetch(`${BASE_URL}/invoices`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${API_KEY}`,
      'Content-Type': 'application/json',
      'X-Idempotency-Key': `inv-${Date.now()}`
    },
    body: JSON.stringify(factura)
  });

  if (!res.ok) {
    const err = await res.json();
    throw new Error(`Key49 error: ${err.error.code} - ${err.error.message}`);
  }

  const { data } = await res.json();
  return data; // { id, access_key, status: "CREATED", ... }
}

async function esperarAutorizacion(documentId, maxIntentos = 10) {
  for (let i = 0; i < maxIntentos; i++) {
    await new Promise(r => setTimeout(r, 2000));

    const res = await fetch(`${BASE_URL}/invoices/${documentId}`, {
      headers: { 'Authorization': `Bearer ${API_KEY}` }
    });
    const { data } = await res.json();

    if (['AUTHORIZED', 'NOTIFIED'].includes(data.status)) {
      return data; // Éxito
    }
    if (['REJECTED', 'FAILED'].includes(data.status)) {
      throw new Error(`Documento ${data.status}: ${JSON.stringify(data.sri_messages)}`);
    }
  }
  throw new Error('Timeout esperando autorización');
}

async function descargarPDF(documentId, filename) {
  const res = await fetch(`${BASE_URL}/invoices/${documentId}/ride`, {
    headers: { 'Authorization': `Bearer ${API_KEY}` }
  });
  const blob = await res.blob();
  // Guardar blob como archivo...
  return blob;
}

// Uso:
const factura = {
  establishment: "001",
  issue_point: "001",
  sequence_number: "000000042",
  issue_date: new Date().toISOString().slice(0, 10), // "2026-06-09"
  recipient: {
    id_type: "04",
    id: "1790012345001",
    name: "Empresa Cliente S.A.",
    email: "contabilidad@cliente.com"
  },
  items: [{
    main_code: "SVC-001",
    description: "Servicio de desarrollo",
    quantity: 1,
    unit_price: 100.00,
    discount: 0.00,
    taxes: [{ code: "2", rate_code: "4", rate: 15.0 }]
  }],
  payments: [{
    payment_method: "20",
    total: 115.00
  }]
};

try {
  const creada = await emitirFactura(factura);
  console.log('Factura creada:', creada.id);

  const autorizada = await esperarAutorizacion(creada.id);
  console.log('Autorizada:', autorizada.access_key);

  await descargarPDF(creada.id, `factura-${autorizada.access_key}.pdf`);
  console.log('PDF descargado');
} catch (err) {
  console.error('Error:', err.message);
}
```

---

## 10. Checklist para el Agente Pi

Antes de enviar facturas, verifica:

- [ ] Tienes el API Key `k49_...` y lo pasas en el header `Authorization`
- [ ] `issue_date` es la fecha de hoy en zona Ecuador (UTC-5)
- [ ] `sequence_number` es único para el `establishment` + `issue_point`
- [ ] Los `taxes` suman correctamente (subtotal + IVA = `payments.total`)
- [ ] Usas `X-Idempotency-Key` para evitar duplicados
- [ ] Esperas la autorización (polling) antes de considerar la factura como emitida
- [ ] Guardas el `access_key` y el `id` en tu base de datos

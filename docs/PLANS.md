# Planes Comerciales — Key49

> Detalle de los planes SaaS, cuotas, rate limits, precios y políticas de renovación.

---

## Planes disponibles

| Plan       | Cuota mensual | Write RPM | Read RPM | Total RPM | Precio (USD/mes) |
| ---------- | ------------- | --------- | -------- | --------- | ---------------- |
| DEMO       | 25            | 10        | 30       | 40        | Gratis           |
| STARTER    | 100           | 30        | 100      | 130       | _Por definir_    |
| BUSINESS   | 500           | 60        | 200      | 260       | _Por definir_    |
| ENTERPRISE | 5,000         | 200       | 600      | 800       | _Por definir_    |

> **Nota**: Los precios se definirán antes de la salida a producción (Fase 2, v1.0.0).

---

## Detalle por plan

### DEMO

- **Propósito**: evaluación y pruebas del sistema.
- **Cuota**: 25 documentos/mes.
- **Rate limits**: 10 write / 30 read RPM.
- **Vigencia**: 30 días desde el registro.
- **Renovación**: no aplica auto-renovación. El tenant pasa a `expired` al vencer.
- **Certificado digital**: no incluido (el tenant usa su propio .p12).

### STARTER

- **Propósito**: pequeños contribuyentes con volumen bajo de facturación.
- **Cuota**: 100 documentos/mes.
- **Rate limits**: 30 write / 100 read RPM.
- **Vigencia**: 30 días desde la activación.
- **Renovación**: solicitud manual via portal con comprobante de pago.

### BUSINESS

- **Propósito**: empresas con volumen medio de facturación.
- **Cuota**: 500 documentos/mes.
- **Rate limits**: 60 write / 200 read RPM.
- **Vigencia**: 30 días desde la activación.
- **Renovación**: solicitud manual via portal con comprobante de pago.

### ENTERPRISE

- **Propósito**: grandes empresas con alto volumen.
- **Cuota**: 5,000 documentos/mes.
- **Rate limits**: 200 write / 600 read RPM.
- **Vigencia**: 30 días desde la activación.
- **Renovación**: **auto-renovación automática** al vencer (job diario `PlanExpirationService`).

---

## Cuota de documentos

- La cuota se cuenta como documentos **creados** (estado CREATED o posterior) en el período activo.
- Al renovar, el contador `documents_used` se resetea a 0.
- Al agotar la cuota, los requests de creación de documentos retornan HTTP 403 con código `QUOTA_EXCEEDED`.

---

## Rate Limiting

Los rate limits se implementan con ventana deslizante (sliding window) en Redis usando sorted sets.

- **Write RPM**: aplica a endpoints de creación de documentos (`POST /v1/invoices`, `POST /v1/credit-notes`, etc.)
- **Read RPM**: aplica a endpoints de consulta (`GET /v1/invoices`, `GET /v1/documents`, etc.)
- Se ajustan automáticamente al aprobar una renovación de plan.
- Se asignan con los valores del plan DEMO al momento del registro.

---

## Ciclo de vida del plan

```
Registro → DEMO (pending) → Verificación email → DEMO (active)
    → Solicitud renovación → pending → Admin aprueba → Nuevo plan (active)
    → Vencimiento:
        - ENTERPRISE: auto-renovación automática
        - Otros planes: status = expired
```

### Expiración

- Job diario ejecuta a las **00:05 ECT** (`PlanExpirationService`).
- Evalúa todos los tenants con `status = 'active'` y `plan_expires_at < now`.
- Tenants ENTERPRISE se auto-renuevan (nuevo período de 30 días, cuota reseteada).
- Todos los demás pasan a `status = 'expired'`.

### Renovación manual

1. El tenant accede a `/portal/plan` en el portal web.
2. Selecciona el plan deseado y adjunta comprobante de pago.
3. Se crea un registro en `plan_renewals` con `status = 'pending'`.
4. Un administrador revisa la solicitud en `/v1/admin/renewals`.
5. Al aprobar:
   - Se actualiza `plan_type`, `document_quota`, `plan_starts_at`, `plan_expires_at`.
   - Se resetea `documents_used = 0`.
   - Se ajustan `rate_limit_write_rpm` y `rate_limit_read_rpm` según el nuevo plan.
   - Se invalida el caché del tenant en Redis.
6. Al rechazar, se registra el motivo y se notifica al tenant.

---

## Enum Java

Los planes se definen como enum en `auracore.key49.core.model.enums.PlanType`:

```java
public enum PlanType {
    DEMO("demo", 25, 10, 30),
    STARTER("starter", 100, 30, 100),
    BUSINESS("business", 500, 60, 200),
    ENTERPRISE("enterprise", 5000, 200, 600);
    // code, defaultQuota, writeRpm, readRpm
}
```

No requiere tabla en base de datos — los planes son datos estables que cambian solo con redeploy.

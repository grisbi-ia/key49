# Integración ERP ↔ SRI — Descarga de comprobantes recibidos

> **Versión**: 1.0 (2026-08-18)
> **Aplica a**: AuraCore ERP (Quarkus/Java, Postgres 15) y Powerfin ERP (OpenXava/Java, Postgres 9.6)
> **Origen**: Ingeniería inversa validada en producción (jul/2026) — Key49-Fetch (worker interno)
>
> **Alcance**: documento de referencia cruzada para la integración de los ERPs
> (AuraCore/Powerfin) con el SRI para la **descarga de comprobantes recibidos**.
> El comportamiento y las políticas del cliente SRI de **Key49** (emisión) están
> consolidados en [`OPERATIONS.md`](OPERATIONS.md#servicio-sri--comportamiento-del-cliente).

---

## 1. Visión general

El SRI no ofrece una API pública para listar los comprobantes recibidos de un RUC.
La única vía sin login automatizado es:

1. El usuario (contador) consulta sus comprobantes en el portal SRI y **descarga el
   archivo TXT** de la pantalla "Gestor" (acción manual normal, 1 minuto).
2. El usuario **carga el TXT al ERP** (drag & drop / selector de archivo) y pulsa
   **"Sincronizar"**.
3. El ERP **descarga cada XML** usando el *web service público de autorización*
   `AutorizacionComprobantesOffline` — solo se necesita la clave de acceso
   (49 dígitos). **No requiere sesión, login ni captcha.**
4. El ERP **genera el PDF** (RIDE) desde el XML y almacena ambos junto al registro.

**Ventajas**: la credencial SRI nunca sale del cliente; cero captcha; cero riesgo de
bloqueo por automatización del portal; el flujo es bajo demanda dentro del ERP.

```
┌──────────────┐   1. consulta    ┌──────────────┐   2. sube TXT    ┌──────────────────┐
│ Portal SRI   │ ───────────────▶ │  Contador    │ ───────────────▶ │ ERP (AuraCore/   │
│ (Gestor)     │   (humano)       │              │   "Sincronizar"  │  Powerfin)       │
└──────────────┘                  └──────────────┘                  │                  │
                                                                    │ 3. SOAP offline  │
                                          ┌───────────────────────▶ │    (por clave)   │
                                          │  4. XML + PDF          └────────┬─────────┘
                                          │                                │ 5. guarda
                                   ┌──────┴───────┐                 ┌───────▼────────┐
                                   │ SRI (web     │                 │ Postgres + disco│
                                   │ service pub.)│                 └────────────────┘
                                   └──────────────┘
```

---

## 2. Formato del archivo TXT (pantalla "Gestor" del SRI)

- **Encoding**: ISO-8859-1 (latin-1). **Separador**: tab `\t`.
- **Primera línea**: cabecera con nombres de columna (se debe omitir).
- **12 columnas** (los campos vacíos aparecen como tabulaciones seguidas):

| # | Columna | Ejemplo (real) |
|---|---------|----------------|
| 0 | `RUC_EMISOR` | `0190055671001` |
| 1 | `RAZON_SOCIAL_EMISOR` | `TOCASA S.A.` |
| 2 | `TIPO_COMPROBANTE` | `Factura` |
| 3 | `SERIE_COMPROBANTE` | `002-500-005401518` |
| 4 | `CLAVE_ACCESO` | `0307202601019005567100120025000054015180540151811` |
| 5 | `FECHA_AUTORIZACION` | `03/07/2026 06:52:16` |
| 6 | `FECHA_EMISION` | `03/07/2026` |
| 7 | `IDENTIFICACION_RECEPTOR` | `0195160252001` |
| 8 | `VALOR_SIN_IMPUESTOS` | `8.69` |
| 9 | `IVA` | `1.3` |
| 10 | `IMPORTE_TOTAL` | `9.99` |
| 11 | `NUMERO_DOCUMENTO_MODIFICADO` | *(vacío; solo NC/ND)* |

**Tipos de comprobante posibles**: `Factura`, `Liquidación de compra`, `Nota de
crédito`, `Nota de débito`, `Comprobante de retención`, `Guía de remisión`.

> ⚠️ **Dato validado en producción**: el TXT puede listar comprobantes que el
> webservice de autorización **no tiene registrados** (en una muestra real: 3 de 13).
> El ERP debe mostrar esos casos como `NO REGISTRADA` para que el contador investigue
> (posible anulación o emisión no autorizada), **no** como error del sistema.

---

## 3. Servicio SOAP de autorización offline

### 3.1 Endpoint

```
POST https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline
Content-Type: text/xml; charset=UTF-8
SOAPAction: ""
```

> ⚠️ El servidor responde **302 intermitente** hacia su IP directa
> (`https://181.113.227.222`). **Siempre seguir redirecciones** (hasta 3 saltos)
> repitiendo el mismo POST. Sin esto se producen fallos aleatorios.

### 3.2 Request (envelope)

```xml
<x:Envelope xmlns:x="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:ec="http://ec.gob.sri.ws.autorizacion">
  <x:Header/>
  <x:Body>
    <ec:autorizacionComprobante>
      <claveAccesoComprobante>{CLAVE_49_DIGITOS}</claveAccesoComprobante>
    </ec:autorizacionComprobante>
  </x:Body>
</x:Envelope>
```

### 3.3 Response — comprobante autorizado

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
 <soap:Body>
  <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
   <RespuestaAutorizacionComprobante>
    <claveAccesoConsultada>3107202601179322067100120010010000407858765432117</claveAccesoConsultada>
    <numeroComprobantes>1</numeroComprobantes>
    <autorizaciones>
     <autorizacion>
      <estado>AUTORIZADO</estado>
      <numeroAutorizacion>3107202601179322067100120010010000407858765432117</numeroAutorizacion>
      <fechaAutorizacion>2026-07-31T11:38:24-05:00</fechaAutorizacion>
      <comprobante>&lt;factura ...&gt;...&lt;/factura&gt;</comprobante>
     </autorizacion>
    </autorizaciones>
   </RespuestaAutorizacionComprobante>
  </ns2:autorizacionComprobanteResponse>
 </soap:Body>
</soap:Envelope>
```

El XML del comprobante viene **escapado como entidades HTML** dentro de
`<comprobante>`. Al parsear el SOAP con un parser XML (DOM/StAX), `getTextContent()`
ya entrega el XML desescapado.

### 3.4 Response — clave no registrada

```xml
<RespuestaAutorizacionComprobante>
  <claveAccesoConsultada>...</claveAccesoConsultada>
  <numeroComprobantes>0</numeroComprobantes>
  <autorizaciones/>
</RespuestaAutorizacionComprobante>
```

### 3.5 Estados posibles

| `estado` / señal | Significado | Acción del ERP |
|---|---|---|
| `AUTORIZADO` + `<comprobante>` | Descarga exitosa | Validar clave, guardar XML, generar PDF |
| `NO AUTORIZADO` | Clave existe pero sin autorización | Marcar `NO_AUTORIZADO`, no reintentar |
| `numeroComprobantes=0` | Clave no registrada en el SRI | Marcar `NO_REGISTRADA`, no reintentar |
| HTTP ≠ 200 / error de red | Transitorio | Reintentar con backoff (máx 3) |
| XML sin `<claveAcceso>` o clave distinta | Respuesta corrupta/equivocada | Descartar y reportar `ERROR_VALIDACION` |

---

## 4. Políticas de operación (críticas — aprendidas en producción)

| # | Regla | Razón |
|---|-------|-------|
| 1 | **Consultas estrictamente secuenciales** (nunca paralelas desde la misma IP) | El SRI rechaza/resetea conexiones simultáneas (verificado: 100% de fallos con 2 en paralelo) |
| 2 | **Ritmo mínimo 300 ms entre consultas** (aleatorio 150–450 ms) | Evita detección de automatización |
| 3 | **Seguir redirecciones 302** (repetir POST, hasta 3 saltos) | Endpoint redirige a IP directa de forma intermitente |
| 4 | **Reintentar solo errores transitorios** (red/HTTP): 3 intentos, backoff 2s/4s | Los estados permanentes (NO REGISTRADA, NO AUTORIZADO) no cambian al reintentar |
| 5 | **Timeout por consulta: 20–30 s** | El servicio a veces tarda; no cortar prematuro |
| 6 | **Validar clave dentro del XML** antes de guardar | Evita persistir respuestas corruptas |
| 7 | **TXT leído como ISO-8859-1**; respuesta SOAP como UTF-8 | Encoding real del SRI |
| 8 | **Verificar si la clave ya existe** antes de consultar | Evita consultas duplicadas (el ERP ya tiene el XML) |
| 9 | **Rango de fechas**: validar que la emisión esté dentro del período consultado | Detección temprana de TXT equivocado |

**Capacidad observada**: ~1 consulta/0.3–1 s sin bloqueo; lotes de cientos de
documentos son viables con pausas. Para miles, dividir en corridas con confirmación.

---

## 5. Generación del PDF (RIDE)

El PDF se genera **localmente desde el XML** (nunca se descarga del portal).

Opciones para Java:

1. **OpenPDF** (`com.github.librepdf:openpdf`) o **iText 5.5.x** — generación
   programática. Recomendado para empezar (ver `RidePdfGenerator.java` de referencia).
2. **Apache FOP + XSL-FO** — transformación declarativa XML→FO→PDF; más mantenible
   si el layout cambia seguido.
3. **Reutilizar el generador RIDE de Key49** (plataforma Quarkus de facturación):
   si AuraCore comparte equipo con Key49, el generador de RIDE de comprobantes
   emitidos aplica directamente a recibidos (mismo XML, mismos campos).

Contenido mínimo del RIDE: logo SRI, razón social y RUC del emisor, datos del
comprobante (serie, secuencial, fecha, clave de acceso), detalle de líneas,
totales e impuestos, número de autorización y fecha, y el hash/firma del XML.

---

## 6. Modelo de datos sugerido

Válido para Postgres 15 (AuraCore) y Postgres 9.6 (Powerfin — mismo DDL, sin
funciones modernas).

```sql
CREATE TABLE sri_received_documents (
    document_id       BIGSERIAL PRIMARY KEY,
    company_ruc       VARCHAR(13)  NOT NULL,          -- RUC del contribuyente (receptor)
    access_key        VARCHAR(49)  NOT NULL UNIQUE,   -- clave de acceso
    document_type     VARCHAR(2)   NOT NULL,          -- 01..06 (código SRI)
    emission_date     DATE         NOT NULL,
    authorization_date TIMESTAMPTZ,
    emitter_ruc       VARCHAR(13)  NOT NULL,
    emitter_name      VARCHAR(300),
    total_amount      NUMERIC(14,2),
    status            VARCHAR(20)  NOT NULL DEFAULT 'DOWNLOADED',
                      -- DOWNLOADED | NO_REGISTRADA | NO_AUTORIZADO | ERROR_VALIDACION
    xml_content       XML,
    pdf_content       BYTEA,
    sync_source_file  VARCHAR(255),                   -- nombre del TXT origen
    synced_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sri_rec_company_date ON sri_received_documents (company_ruc, emission_date);
CREATE INDEX idx_sri_rec_status        ON sri_received_documents (status);
```

Notas:
- `access_key` es la clave natural de negocio → `UNIQUE` permite "upsert" idempotente
  (re-sincronizar el mismo TXT no duplica).
- Los XMLs también pueden ir a disco/MinIO con la estructura
  `{empresa}/{anio}/{mes}/{tipo}/{access_key}.xml`; en ese caso `xml_content`
  puede ser un `VARCHAR` con la ruta.
- Powerfin (PG 9.6) soporta `XML` y `BYTEA` sin cambios.

---

## 7. Flujo REST sugerido (AuraCore / cualquier backend)

```
POST /api/sri/received/sync          multipart: file=<gestor.txt>, companyRuc
  → 200 { "total": 13, "downloaded": 10, "noRegistrada": 3, "noAutorizada": 0,
          "errors": [ { "accessKey": "...", "status": "NO_REGISTRADA" } ] }

GET  /api/sri/received?companyRuc=...&from=2026-07-01&to=2026-07-31
  → lista de documentos sincronizados

GET  /api/sri/received/{accessKey}/xml    → application/xml
GET  /api/sri/received/{accessKey}/pdf    → application/pdf
```

Implementación de referencia (Java 8+ puro, sin dependencias de framework):
`docs/reference-java/com/key49/sri/` — portar directo a Quarkus u OpenXava.

---

## 8. Checklist de implementación

- [ ] Parser TXT (ISO-8859-1, 12 columnas, omitir cabecera, validar clave 49 dígitos)
- [ ] Cliente SOAP con follow-redirects manual y timeout 30 s
- [ ] Consultas secuenciales con delay 150–450 ms
- [ ] Clasificación de estados (AUTORIZADO / NO REGISTRADA / NO AUTORIZADO / ERROR)
- [ ] Validación de clave dentro del XML
- [ ] Upsert por `access_key` (idempotente)
- [ ] Generación de PDF RIDE local
- [ ] Pantalla con progreso (n descargados / pendientes / estados) y resumen final
- [ ] Log de auditoría (archivo origen, usuario, fecha, resultados)

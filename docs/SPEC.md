# Key49 — Facturación Electrónica para Desarrolladores

## Visión del Producto

Key49 es una plataforma SaaS multi-tenant de facturación electrónica para Ecuador que expone APIs REST para que desarrolladores integren la emisión, firma, envío al SRI y entrega de comprobantes electrónicos desde cualquier sistema (ERP, POS, e-commerce, apps móviles).

**No es** un portal de facturación para usuarios finales. Es infraestructura para desarrolladores.

## Problema que Resuelve

Los desarrolladores ecuatorianos que necesitan emitir comprobantes electrónicos enfrentan:

- Implementar firma XAdES-BES desde cero (complejo, propenso a errores)
- Consumir servicios SOAP del SRI (legacy, sin documentación moderna)
- Manejar estados asíncronos (RECIBIDA → AUTORIZADO/NO AUTORIZADO)
- Generar el RIDE (PDF) conforme a la normativa
- Almacenar comprobantes por 7 años (obligación legal)
- Adaptarse a cambios frecuentes del SRI en XSD y normativa

Key49 abstrae toda esa complejidad detrás de una API REST moderna.

## Contexto Normativo Ecuador (vigente abril 2026)

### Resolución NAC-DGERCGC25-00000017

- **Desde 1 enero 2026**: transmisión de comprobantes al SRI debe ser en **tiempo real** (se eliminó el plazo de 4 días hábiles)
- **Emisión el mismo día**: la factura electrónica debe emitirse el mismo día de la venta (física o digital). Key49 valida que `issue_date` corresponda al día actual en zona horaria `America/Guayaquil` (UTC-5).
- Anulación de comprobantes: hasta el día 7 del mes siguiente a la emisión
- Facturas a "consumidor final" no pueden anularse una vez transmitidas
- Notas de crédito solo en casos expresamente contemplados por el reglamento

### Zona Horaria

Ecuador Continental opera en `America/Guayaquil` (UTC-5). Toda validación de "fecha del día actual" en Key49 usa esta zona horaria, independientemente de dónde esté desplegado el servidor. La constante se configura en `KEY49_TIMEZONE=America/Guayaquil`.

### Anulación de Comprobantes

El SRI **no ofrece un servicio SOAP** para anular comprobantes electrónicos. La anulación se realiza manualmente por el contribuyente en el portal web del SRI (`sri.gob.ec > Servicios en Línea > Comprobantes Electrónicos > Anulación`).

**Key49 y la anulación:**

- Key49 permite marcar un documento como `VOIDED` localmente (`POST /invoices/:id/void`) para trazabilidad interna.
- El estado `VOIDED` es local — NO se comunica al SRI.
- El contribuyente debe anular el comprobante en el portal del SRI de forma independiente.
- Restricciones normativas que Key49 valida:
  - Solo documentos en estado `AUTHORIZED` o `NOTIFIED` pueden anularse.
  - Plazo: hasta el día 7 del mes siguiente a la emisión (se calcula en zona `America/Guayaquil`).
  - Facturas a consumidor final (`recipient_id_type = "07"`) no pueden anularse.

### Ficha Técnica v2.32 (noviembre 2025)

- Anexo 25: requisitos obligatorios para operadores de transporte comercial
- Tabla 32: códigos auxiliares específicos para transporte
- Versiones XSD vigentes por tipo de documento (ver sección Documentos Soportados)

### Entidades Certificadoras de Firma Electrónica

- Security Data
- Banco Central del Ecuador
- Otras entidades acreditadas por ARCOTEL

Los certificados se entregan en formato PKCS#12 (.p12) con contraseña.

## Documentos Electrónicos Soportados

| Tipo                     | Código SRI | Versión XSD | Prioridad |
| ------------------------ | ---------- | ----------- | --------- |
| Factura                  | 01         | 2.1.0       | Fase 1    |
| Nota de Crédito          | 04         | 1.1.0       | Fase 3    |
| Nota de Débito           | 05         | 1.0.0       | Fase 3    |
| Guía de Remisión         | 06         | 1.1.0       | Fase 3    |
| Comprobante de Retención | 07         | 2.0.0 (ATS) | Fase 3    |
| Liquidación de Compra    | 03         | 1.1.0       | Fase 3    |

## Flujo Principal de un Comprobante

```
Cliente API                    Key49                         SRI
    |                            |                            |
    |-- POST /invoices --------->|                            |
    |<-- 202 Accepted (id, key)--|                            |
    |                            |-- Genera XML               |
    |                            |-- Valida vs XSD            |
    |                            |-- Genera clave acceso 49d  |
    |                            |-- Firma XAdES-BES (.p12)   |
    |                            |-- SOAP RecepcionComprobantes -->|
    |                            |<-- RECIBIDA / DEVUELTA --------|
    |                            |                            |
    |                            |-- SOAP AutorizacionComprobantes -->|
    |                            |<-- AUTORIZADO / NO AUTORIZADO -----|
    |                            |                            |
    |                            |-- Genera RIDE (PDF + QR)   |
    |                            |-- Almacena XML + RIDE      |
    |                            |-- Email al receptor        |
    |<-- Webhook (estado final)--|                            |
    |                            |                            |
    |-- GET /invoices/:id ------>|                            |
    |<-- {status, xml, ride} ----|                            |
```

## Web Services del SRI

### Endpoints SOAP

**Ambiente de Pruebas (Certificación):**

- Recepción: `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl`
- Autorización: `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl`

**Ambiente de Producción:**

- Recepción: `https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl`
- Autorización: `https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl`

### Operaciones SOAP

- `validarComprobante(xml)` — Recibe XML en base64, retorna estado RECIBIDA o DEVUELTA con mensajes de error
- `autorizacionComprobante(claveAcceso)` — Consulta por clave de acceso de 49 dígitos, retorna AUTORIZADO o NO AUTORIZADO

### Clave de Acceso (49 dígitos)

Estructura: `[fecha8][tipoDoc2][ruc13][ambiente1][serie7][secuencial9][codigoNumerico8][tipoEmision1]`

- fecha: ddmmaaaa (8 dígitos)
- tipoComprobante: 01=factura, 04=NC, 05=ND, 06=GR, 07=CR, 03=LC (2 dígitos)
- ruc: RUC del emisor (13 dígitos)
- tipoAmbiente: 1=pruebas, 2=producción (1 dígito)
- serie: establecimiento(3) + puntoEmision(3) + padding (7 dígitos, se completa con los 3+3 del estab+pto más un "0" extra)
  - NOTA: La estructura real es: establecimiento(3) + puntoEmision(3) = 6 dígitos de serie
- secuencial: número secuencial del comprobante (9 dígitos, completar con ceros a la izquierda)
- codigoNumerico: código numérico aleatorio de 8 dígitos
- tipoEmision: 1=normal (1 dígito)
- dígito verificador: módulo 11 del total (1 dígito)

Total: 8+2+13+1+6+9+8+1+1 = 49 dígitos

### Firma Electrónica XAdES-BES

- Estándar: XAdES-BES (XML Advanced Electronic Signatures - Basic Electronic Signature)
- Esquema: XAdES 1.3.2
- Encoding: UTF-8
- Tipo: Enveloped signature
- Nodo padre de firma: `comprobante`
- Certificado: PKCS#12 (.p12) con contraseña
- El nodo `ds:Signature` se inserta dentro del XML del comprobante

## Modelo Multi-Tenant

Cada tenant (cliente de la API) tiene:

- `tenant_id` UUID único
- RUC del emisor
- Razón social
- Certificado .p12 cifrado (AES-256-GCM)
- Contraseña del certificado cifrada
- Ambiente configurado (pruebas/producción)
- API Key (Bearer token)
- Webhook URL para callbacks
- Rate limit configurable (default: 100 req/min)

> **Nota**: Key49 NO gestiona secuenciales ni puntos de emisión. El `sequence_number`, `establishment` e `issue_point` los proporciona el cliente en cada request.

## Requisitos No Funcionales

- **Latencia**: firma + envío al SRI < 5 segundos (P95)
- **Throughput**: 50 comprobantes/segundo por instancia
- **Disponibilidad**: 99.5% (excluye downtime del SRI)
- **Retención**: XML y RIDE almacenados 7 años mínimo
- **Seguridad**: certificados cifrados at-rest, API keys hasheadas, TLS everywhere
- **Observabilidad**: métricas Prometheus, logs estructurados JSON, tracing OpenTelemetry
- **Zona horaria**: toda lógica temporal usa `America/Guayaquil` (UTC-5)

## Stack Tecnológico

- **Runtime**: Java 25 LTS
- **Framework**: Quarkus 3.34 (reactive)
- **Base de datos**: PostgreSQL 16
- **Mensajería**: RabbitMQ 3.13+
- **Almacenamiento objetos**: MinIO (S3-compatible)
- **Cache**: Redis 7
- **Firma digital**: Apache Santuario + BouncyCastle
- **Cliente SOAP**: Apache CXF / Jakarta XML WS
- **PDF**: OpenPDF o Apache FOP
- **Email**: Quarkus Mailer + Qute templates
- **Infraestructura**: DokPloy + Traefik (Debian)
- **Monitoreo**: Micrometer + Prometheus + Grafana

## Glosario

| Término         | Significado                                                           |
| --------------- | --------------------------------------------------------------------- |
| SRI             | Servicio de Rentas Internas (autoridad tributaria de Ecuador)         |
| RIDE            | Representación Impresa de Documento Electrónico (PDF)                 |
| XAdES-BES       | XML Advanced Electronic Signatures - Basic Electronic Signature       |
| Clave de acceso | Identificador único de 49 dígitos para cada comprobante               |
| Tenant          | Cliente de la API (empresa que emite comprobantes)                    |
| PKCS#12         | Formato de archivo (.p12) que contiene el certificado y clave privada |
| XSD             | XML Schema Definition (esquemas de validación del SRI)                |
| WSDL            | Web Services Description Language (contratos SOAP del SRI)            |
| ATS             | Anexo Transaccional Simplificado (formato de retenciones)             |

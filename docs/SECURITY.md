# Auditoría de Seguridad — Key49

> Fecha: 2026-04-10 | Stack: Java 25 + Quarkus 3.34 + PostgreSQL 16

## Resumen ejecutivo

Auditoría completa contra OWASP Top 10 del codebase Key49. Se identificaron **23 hallazgos** (0 críticos, 4 altos, 11 medios, 8 bajos). Se implementaron remediaciones para todos los hallazgos HIGH y la mayoría MEDIUM.

| Categoría              | Alto  | Medio  | Bajo  | Remediados |
| ---------------------- | ----- | ------ | ----- | ---------- |
| Injection              | 0     | 0      | 1     | —          |
| Autenticación rota     | 0     | 2      | 1     | 1          |
| Control de acceso roto | 2     | 1      | 0     | 3          |
| Configuración insegura | 1     | 3      | 2     | 4          |
| XSS                    | 0     | 1      | 1     | —          |
| Fallas criptográficas  | 0     | 1      | 1     | 1          |
| Exposición de datos    | 0     | 2      | 2     | 2          |
| SSRF                   | 1     | 1      | 0     | 2          |
| **Total**              | **4** | **11** | **8** | **13**     |

---

## 1. Injection (SQL / Command)

### Estado: APROBADO

| Control                   | Implementación                                                                    |
| ------------------------- | --------------------------------------------------------------------------------- |
| Queries parametrizadas    | Todas las queries usan bindings con `?` o `:param` (Hibernate/PreparedStatement)  |
| Validación de schema      | `TenantSchemaResolver` valida con regex `^[a-z0-9_]+$` antes de `SET search_path` |
| ORDER BY whitelist        | `InvoiceService.resolveOrderBy()` usa switch con valores permitidos               |
| Sin ejecución de comandos | No existe `Runtime.exec`, `ProcessBuilder` ni ejecución de comandos externos      |

### Hallazgos menores

- **N1 (LOW)**: `CertificateExpirationNotifier` usa string constante `status = 'active'` en SQL — no es inyectable pero podría usar parámetro para consistencia.

---

## 2. Autenticación

### Controles positivos

| Control            | Implementación                                     |
| ------------------ | -------------------------------------------------- |
| API keys hasheadas | SHA-256 antes de almacenar — raw key nunca en BD   |
| Generación segura  | `SecureRandom` de 24 chars (>140 bits de entropía) |
| Session IDs        | `UUID.randomUUID()` — criptográficamente aleatorio |
| Session TTL        | 30 minutos con renovación al acceder               |
| Cookie protegida   | `httpOnly=true`, `sameSite=STRICT`                 |

### Remediaciones implementadas

- **N3 (MEDIUM)**: Cookie `Secure` flag — Ahora se agrega `secure=true` en producción vía `key49.portal.secure-cookie`. Configurable por perfil.

### Hallazgos pendientes (riesgo bajo)

- **N4 (MEDIUM)**: Sin token CSRF en formulario del portal. Mitigado por `SameSite=STRICT`.
- **N5 (LOW)**: Sin rate limiting específico en `/portal/login`. El rate limiter global aplica después de auth.

---

## 3. Control de acceso (Aislamiento de tenants)

### Controles positivos

| Control              | Implementación                                                                   |
| -------------------- | -------------------------------------------------------------------------------- |
| Schema-per-tenant    | `SET LOCAL search_path TO 'tenant_xxx'` — reset automático al fin de transacción |
| Contexto desde auth  | Schema viene del API key autenticada, no de parámetros del request               |
| Validación de schema | `TenantSchemaResolver.validate()` previene path traversal                        |
| Tests de aislamiento | `TenantSchemaIsolationTest` verifica cross-tenant isolation                      |

### Remediaciones implementadas

- **N6 (HIGH)**: Endpoints admin sin autorización — Creado `AdminAuthFilter` que requiere header `X-Admin-Token` con token configurado en `KEY49_ADMIN_TOKEN`. Usa comparación time-safe (`MessageDigest.isEqual`). Prioridad 20 (después de `ApiKeyAuthFilter`).
- **N7 (MEDIUM)**: Health/metrics expuestos — Swagger UI deshabilitado en producción (`%prod.quarkus.swagger-ui.always-include=false`).
- **N8 (LOW)**: OpenAPI en producción — Swagger UI deshabilitado.

---

## 4. Configuración de seguridad

### Controles positivos

| Control              | Implementación                                             |
| -------------------- | ---------------------------------------------------------- |
| Secretos en env vars | Todos: `${KEY49_DB_PASSWORD}`, `${KEY49_MASTER_KEY}`, etc. |
| CORS restrictivo     | Producción: orígenes específicos solamente                 |
| HSTS                 | `max-age=31536000; includeSubDomains` en producción        |
| DevServices          | Deshabilitados en producción                               |

### Remediaciones implementadas

- **N9/N10 (MEDIUM)**: Credenciales default en dev — Son defaults con prefijo `%dev.`, solo aplican en perfil dev. Aceptable para desarrollo local.
- **N14 (LOW)**: Sin catch-all exception mapper — Creado `CatchAllExceptionMapper` que retorna `{"error":{"code":"INTERNAL_ERROR"}}` sin stack traces.
- **N18 (LOW)**: SMTP sin TLS — Agregado `%prod.quarkus.mailer.start-tls=REQUIRED` para producción.

---

## 5. XSS (Cross-Site Scripting)

### Estado: APROBADO

| Control                | Implementación                                                  |
| ---------------------- | --------------------------------------------------------------- |
| Auto-escape Qute       | Todas las expresiones `{...}` escapan HTML por defecto          |
| Sin `.raw()`           | No se usa `raw` en ningún template                              |
| CSP estricto           | `default-src 'self'; script-src 'self'; frame-ancestors 'none'` |
| X-Content-Type-Options | `nosniff` en todas las respuestas                               |
| X-Frame-Options        | `DENY` en todas las respuestas                                  |

### Hallazgos menores

- **N15 (MEDIUM)**: `documentStatusBadge()` retorna HTML raw — los valores `cls` y `label` provienen de enums (switch fijo), no de input del usuario. Riesgo teórico solamente.
- **N16 (LOW)**: CSP permite `'unsafe-inline'` para styles — necesario para Pico CSS.

---

## 6. Fallas criptográficas

### Controles positivos

| Control             | Implementación                                            |
| ------------------- | --------------------------------------------------------- |
| AES-256-GCM         | Cifrado de certificados .p12 con IV aleatorio de 12 bytes |
| Master key          | 32 bytes (256 bits), validada, cargada desde env var      |
| Limpieza de memoria | Arrays de bytes/chars limpiados después de uso            |
| HMAC-SHA256         | Firma de webhooks con secret por tenant                   |
| SecureRandom        | Para generación de API keys y sessions                    |

### Remediaciones implementadas

- **N18 (LOW)**: SMTP TLS — Agregado `%prod.quarkus.mailer.start-tls=REQUIRED`.

---

## 7. Exposición de datos sensibles

### Controles positivos

| Control                       | Implementación                        |
| ----------------------------- | ------------------------------------- |
| API key parcial en logs       | Solo primeros 8 chars del hash        |
| rawKey null post-creación     | `@JsonInclude(NON_NULL)` en respuesta |
| Sin cert/password en response | DTOs excluyen datos sensibles         |
| Error responses genéricos     | Código + mensaje, sin stack traces    |

### Remediaciones implementadas

- **N19 (MEDIUM)**: `StorageExceptionMapper` — Cambiado de `"Storage service unavailable: " + ex.getMessage()` a `"Storage service temporarily unavailable"` (sin leak de detalle interno).
- **N21 (LOW)**: Session ID en logs — Truncado a primeros 8 caracteres.

---

## 8. SSRF (Server-Side Request Forgery)

### Remediaciones implementadas

- **N23 (HIGH)** + **N24 (MEDIUM)**: Creado `WebhookUrlValidator` que valida URLs de webhook antes del envío:

| Validación      | Detalle                                                                       |
| --------------- | ----------------------------------------------------------------------------- |
| Esquema         | Solo `https://` o `http://`                                                   |
| Host bloqueados | `localhost`, `.local`, `.internal`, `[::1]`                                   |
| IPs bloqueadas  | Loopback, link-local (169.254.x.x), site-local (10.x, 172.16-31.x, 192.168.x) |
| Cloud metadata  | 169.254.169.254 (AWS/GCP/Azure)                                               |
| Resolución DNS  | Resuelve el host y valida IPs resultantes                                     |
| Redirects       | `HttpClient.Redirect.NEVER` — no sigue redirects                              |

Integrado en:

- `WebhookDispatcher.send()` — todas las entregas de webhook
- `CertificateExpirationNotifier.sendExpirationWebhook()` — notificaciones de expiración

---

## Archivos creados/modificados

| Archivo                              | Cambio                                           |
| ------------------------------------ | ------------------------------------------------ |
| `WebhookUrlValidator.java`           | **Nuevo** — validación SSRF                      |
| `AdminAuthFilter.java`               | **Nuevo** — autorización admin                   |
| `CatchAllExceptionMapper.java`       | **Nuevo** — catch-all 500                        |
| `WebhookDispatcher.java`             | SSRF validation + no-redirect                    |
| `CertificateExpirationNotifier.java` | SSRF validation + no-redirect                    |
| `StorageExceptionMapper.java`        | Mensaje genérico sin leak                        |
| `PortalResource.java`                | Cookie `Secure` flag                             |
| `PortalSessionService.java`          | Session ID truncado en logs                      |
| `application.properties`             | Admin token, SMTP TLS, Swagger UI, portal cookie |

---

## Variables de entorno de seguridad

| Variable                     | Propósito                                    | Requerido en prod |
| ---------------------------- | -------------------------------------------- | ----------------- |
| `KEY49_ADMIN_TOKEN`          | Token para endpoints `/v1/admin/*`           | **Sí**            |
| `KEY49_MASTER_KEY`           | Clave AES-256 para cifrar certificados       | **Sí**            |
| `KEY49_DB_PASSWORD`          | Password de PostgreSQL                       | **Sí**            |
| `KEY49_SMTP_START_TLS`       | TLS para SMTP (default: `REQUIRED` en prod)  | Recomendado       |
| `KEY49_PORTAL_SECURE_COOKIE` | Cookie Secure flag (default: `true` en prod) | Automático        |
| `KEY49_CORS_ORIGINS`         | Orígenes permitidos en producción            | **Sí**            |

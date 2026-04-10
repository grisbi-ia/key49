# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/),
y este proyecto adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [0.25.4] - 2026-04-10

### Agregado

- **Dashboard de métricas del tenant en portal** (T-081): nueva página `/portal/metrics`
- Cards de resumen: Autorizados, En proceso, Fallidos, Total
- Gráfico de barras CSS: documentos emitidos por día (últimos 30 días)
- Último documento emitido con enlace a detalle y badge de estado
- Estado del certificado de firma: vigente/por vencer/vencido/sin certificado
- Navegación actualizada en layout con enlaces a Documentos y Métricas
- 6 tests E2E (PortalEndToEndTest): métricas, contadores, certificado, gráfico, nav, autenticación
- 10 tests unitarios (PortalMetricsTest): DailyCount record, lógica de estado de certificado

## [0.25.3] - 2026-04-10

### Agregado

- **Retry manual desde portal web** (T-080): botón "Reintentar" en documentos FAILED con fecha de hoy
- `POST /portal/documents/{id}/retry`: endpoint que valida estado FAILED + fecha emisión = hoy, resetea retry_count a 0, transiciona a CREATED y republica en cola de firma
- Mensajes de feedback en vista de detalle: éxito, estado inválido, fecha inválida
- Registro de auditoría (`portal.retry`) con actor, IP y documento
- 13 tests unitarios (PortalRetryTest): transiciones de estado, validación de fecha, elegibilidad
- 6 tests E2E (PortalEndToEndTest): botón visible/oculto según estado y fecha, rechazo por estado/fecha, autenticación requerida

## [0.25.2] - 2026-04-10

### Agregado

- **Structured logging con contexto de tenant** (T-079): MDC con `tenant` y `documentId` en todos los logs
- `MdcContext`: utilidad centralizada para set/clear de campos MDC (`tenant`, `documentId`)
- `MdcFilter`: filtro HTTP (priority 25) que establece MDC desde TenantContext (API) o PortalSession (portal), limpia en respuesta
- MDC en 5 consumers: SignConsumer, SendConsumer, AuthorizeConsumer, NotifyConsumer, DlqConsumer — set al inicio, clear en finally
- MDC en OutboxPoller: set por iteración de tenant, clear tras cada tenant
- Formato de log actualizado: `tenant=%X{tenant} doc=%X{documentId}` añadido al console format
- 12 tests unitarios: 7 en MdcContextTest + 5 en MdcFilterTest
- `PortalAuthFilter.PORTAL_SESSION_ATTR` hecho público para acceso desde MdcFilter

## [0.25.1] - 2026-04-10

### Agregado

- **Alertas SLA y métricas de negocio** (T-078): regla de alerta para detectar documentos sin autorizar fuera de SLA
- `SlaAuthorizationAlertRule`: itera todos los tenants activos y consulta documentos en estados intermedios (CREATED, SIGNED, SENT, RECEIVED) más antiguos que el umbral configurado
- Métrica `key49.sla.breach{tenant, type=authorization_latency}` incrementada por cada tenant con incumplimiento
- Schedule dedicado cada 5 minutos (`alert-evaluator-sla`) en `AlertEvaluator`
- Configuración: `key49.alerts.sla-authorization-minutes` (default 5)
- Notificación vía email y webhook cuando se detectan documentos atascados
- 8 tests unitarios en `SlaAuthorizationAlertRuleTest` (stubs para TenantRepository y TenantConnectionManager)

## [0.25.0] - 2026-04-10

### Agregado

- **Métricas dimensionadas por tenant** (T-077): tag `tenant` en todas las métricas de negocio para desglose por cliente
- `DocumentMetrics` rediseñado: conserva contadores globales backward-compatible y añade métodos tenant-dimensionados
- Contadores con tag tenant: `key49.documents.created{tenant, type}`, `key49.documents.authorized{tenant}`, `key49.documents.failed{tenant, reason}`
- Timers SRI con tag tenant: `key49.sri.latency{tenant, operation=reception|authorization}`
- Contadores de notificación: `key49.email.sent{tenant}`, `key49.email.failed{tenant}`, `key49.webhook.dispatched{tenant}`
- Instrumentación en consumers: `SendConsumer` (timer recepción + rejected), `AuthorizeConsumer` (timer autorización + authorized + rejected), `DlqConsumer` (failed), `NotifyConsumer` (email sent/failed + webhook dispatched)
- Instrumentación en 7 servicios de creación de documentos: `InvoiceService`, `CreditNoteService`, `DebitNoteService`, `WithholdingService`, `PurchaseClearanceService`, `WaybillService`, `RawDocumentService`
- Métricas solo se registran para creaciones reales (no retornos idempotentes)
- Deprecated: `sriReceptionTimer()` y `sriAuthorizationTimer()` sin tenant (mantienen backward-compat)
- Tests: `DocumentMetricsTest` (23 tests — contadores globales, tenant-dimensionados, timers SRI, notificación, aislamiento entre tenants)

## [0.24.3] - 2026-04-10

### Agregado

- **Rotación de certificados .p12 sin downtime** (T-076): permite subir un certificado pendiente sin reemplazar el activo, y activarlo cuando se desee
- Migración `V005__add_pending_certificate.sql`: 5 columnas `pending_certificate_*` en tabla `tenants` (p12, password_enc, subject, expiration, serial)
- Campos `pending_certificate_*` en entidad `Tenant` para almacenar certificado pendiente de activación
- `TenantAdminService.rotateCertificate()`: almacena certificado en campos pending sin modificar el activo ni invalidar caché
- `TenantAdminService.activateCertificate()`: mueve pending→activo atómicamente, limpia pending, invalida caché de tenant y certificado
- Endpoints admin: `POST /v1/admin/tenants/:id/certificate/rotate`, `POST /v1/admin/tenants/:id/certificate/activate`
- Endpoints self-service: `POST /v1/tenant/certificate/rotate`, `POST /v1/tenant/certificate/activate`
- `CertificateStatusResponse.PendingCertificate`: nested record con subject, serial, expiresAt, valid, daysUntilExpiration
- Endpoint `GET /certificate/status` actualizado para incluir info del certificado pendiente cuando existe
- `TenantResponse.CertificateSummary.pendingRotation`: indica si hay rotación pendiente
- Acciones de auditoría: `certificate.rotated`, `certificate.activated`
- Error `NO_PENDING_CERTIFICATE` (422) al intentar activar sin certificado pendiente
- Ventana de gracia inherente: documentos en vuelo continúan firmándose con certificado activo hasta activación
- Tests: `CertificateRotationTest` (4 tests lógica de campos pending), `TenantDtoTest` ampliado (8 tests nuevos para pendingRotation y PendingCertificate)

## [0.24.2] - 2026-04-10

### Agregado

- **Audit log de operaciones sensitivas** (T-075): registro centralizado en `public.audit_log` para todas las operaciones críticas
- Tabla `public.audit_log` con campos `tenant_id`, `actor`, `action`, `resource`, `resource_id`, `ip_address`, `details` (JSONB), `created_at` — migración `V004__create_audit_log.sql`
- Entidad `AuditLog` con `@Table(schema = "public")` — funciona independientemente del `search_path` activo del tenant
- `AuditService.record()`: servicio centralizado `@Transactional` que registra entradas de auditoría
- `AuditService.resolveIp()`: extrae IP del cliente desde `X-Forwarded-For` o `remoteAddress`
- Acciones auditadas: `portal.login`, `portal.logout`, `api_key.created`, `api_key.revoked`, `tenant.created`, `tenant.updated`, `certificate.uploaded`, `document.voided`
- Instrumentación en 12 recursos: `PortalResource`, `ApiKeyResource`, `TenantAdminResource`, `TenantProfileResource`, `InvoiceResource`, `CreditNoteResource`, `DebitNoteResource`, `WithholdingResource`, `PurchaseClearanceResource`, `WaybillResource`
- Endpoint admin `GET /v1/admin/audit-log` con filtros: `tenant_id`, `action`, `date_from`, `date_to`, paginación
- `AuditLogResponse` DTO y `AuditLogAdminResource` para consulta administrativa
- Tests: `AuditServiceTest` (7 tests resolveIp), `AuditLogResponseTest` (4 tests DTO/serialización), `AuditLogAdminResourceTest` (9 tests integración con filtros y paginación)

## [0.24.1] - 2026-04-10

### Agregado

- **Rate limiting granular por endpoint** (T-074): límites independientes para escritura y lectura por tenant
- `EndpointCategory` enum: clasifica métodos HTTP en `WRITE` (POST/PUT/PATCH/DELETE) y `READ` (GET/HEAD/OPTIONS) con suffix para clave Redis
- Campos `rate_limit_write_rpm` (default 30) y `rate_limit_read_rpm` (default 200) en entidad `Tenant`
- Migración `V003__add_granular_rate_limits.sql`: agrega columnas con defaults en tabla `tenants`
- Métrica Micrometer `key49.rate_limit.rejected` con tags `tenant` y `category` para monitoreo de rechazos
- Tests `EndpointCategoryTest` (parametrizados) y `GranularRateLimitTest` (integración) para validar aislamiento de límites

### Cambiado

- `RateLimiter`: acepta `EndpointCategory`, clave Redis ahora `ratelimit:{prefix}:{write|read}`
- `RateLimitFilter`: detecta categoría por método HTTP, selecciona límite write/read del `TenantContext`
- `ApiKeyCacheService`: `CachedApiKeyData` incluye campos granulares, serialización Redis actualizada con backward compatibility
- `ApiKeyAuthFilter`: propaga límites write/read al `TenantContext`
- `TenantResponse` y `UpdateTenantRequest`: incluyen campos `rateLimitWriteRpm` y `rateLimitReadRpm`
- `TenantAdminService`: soporte para actualizar límites granulares por tenant

## [0.24.0] - 2026-04-10

### Agregado

- **Auditoría de seguridad OWASP Top 10** (T-073): auditoría completa del codebase con 23 hallazgos y remediaciones
- `WebhookUrlValidator`: protección SSRF — valida URLs de webhook contra redes internas (loopback, link-local, site-local, metadata cloud 169.254.169.254), resolución DNS obligatoria y solo esquemas HTTP/HTTPS
- `AdminAuthFilter`: autorización dedicada para endpoints `/v1/admin/*` con header `X-Admin-Token` y comparación time-safe (`MessageDigest.isEqual`)
- `CatchAllExceptionMapper`: catch-all para excepciones no manejadas — retorna respuesta genérica sin exponer stack traces ni datos internos
- Cookie `Secure` flag configurable por perfil (`key49.portal.secure-cookie`), activado por defecto en producción
- SMTP TLS requerido en producción (`%prod.quarkus.mailer.start-tls=REQUIRED`)
- Swagger UI deshabilitado en producción (`%prod.quarkus.swagger-ui.always-include=false`)
- Documentación `docs/SECURITY.md` con inventario completo de controles y hallazgos

### Cambiado

- `ApiKeyAuthFilter`: endpoints `/v1/admin/*` excluidos de autenticación por API key (protegidos por `AdminAuthFilter`)
- `WebhookDispatcher`: validación SSRF obligatoria + deshabilitación de redirects (`HttpClient.Redirect.NEVER`)
- `CertificateExpirationNotifier`: validación SSRF + deshabilitación de redirects en webhooks de expiración
- `StorageExceptionMapper`: mensaje genérico sin leak de detalles internos de MinIO/S3
- `PortalSessionService`: session ID truncado a 8 caracteres en logs de logout

### Corregido

- `TenantSchemaResolverTest`: strings corruptas en `@ValueSource` reconstruidas
- `QueryOptimizationTest`: variables truncadas en assertion de índice parcial

## [0.23.3] - 2026-04-10

### Agregado

- **Monitoreo de queries y optimización de índices** (T-072): herramientas de análisis de queries y nuevos índices optimizados
- Migración `V006__add_query_optimization_indexes.sql`: índice parcial `idx_documents_pending` para documentos en tránsito y compuesto `idx_documents_status_type_date` para queries de listado
- Script `db/maintenance/top_queries.sh`: extrae top N queries por tiempo total, frecuencia y promedio desde `pg_stat_statements`
- Script `db/maintenance/explain_pipeline_queries.sh`: ejecuta EXPLAIN ANALYZE en 11 patrones de query del pipeline
- Sección "Monitoreo de queries (pg_stat_statements)" en `DB-ADMIN.md` con instrucciones para habilitar en PostgreSQL local y Docker
- Documentación de cobertura de índices por patrón de acceso y consultas manuales útiles
- 8 tests de integración en `QueryOptimizationTest` que verifican: índice parcial de pendientes, índice compuesto para listados, partition pruning, y cobertura de todos los índices

## [0.23.2] - 2026-04-10

### Agregado

- **Mantenimiento automatizado de PostgreSQL** (T-071): suite completa de scripts de mantenimiento en `db/maintenance/`
- Script `vacuum_analyze.sh`: VACUUM ANALYZE en todos los esquemas tenant + public, con soporte para VACUUM FULL y esquema individual
- Script `tune_autovacuum.sh`: configura `autovacuum_vacuum_scale_factor=0.05` en tabla documents (y particiones) para vacuum más agresivo
- Script `monitor_bloat.sh`: reporta dead tuples, tamaños de tabla/índice, actividad autovacuum, y tablas que necesitan vacuum
- Script `reindex_concurrently.sh`: reconstruye índices sin downtime usando REINDEX CONCURRENTLY
- Crontab recomendado para producción documentado en `DB-ADMIN.md`
- Sección "Mantenimiento automatizado" en `DB-ADMIN.md` con guía operativa de cada script
- 8 tests de integración en `PostgresMaintenanceTest` que verifican: VACUUM ANALYZE, autovacuum tuning, dead tuples tracking, REINDEX CONCURRENTLY, table sizes, y validez de índices

## [0.23.1] - 2026-04-10

### Agregado

- **Particionamiento de tabla `documents`** (T-070): script de migración `V005__partition_documents.sql` que convierte la tabla en particionada por rango mensual sobre `issue_date`
- Script de mantenimiento `db/maintenance/create_monthly_partitions.sh` para crear particiones futuras vía cron
- Partition pruning en queries con filtro de fecha: PostgreSQL escanea solo la partición del mes consultado
- Índice nuevo `idx_documents_created_at` para queries de monitoreo por timestamp
- 8 tests de integración que verifican: enrutamiento a particiones, partition pruning con EXPLAIN, lookups por PK sin `issue_date` en WHERE, partición default, y operaciones CRUD
- Sección "Particionamiento de documents" en `DB-ADMIN.md` con guía operativa completa
- Documentación de particionamiento en `DATABASE.md` con tabla de impacto en constraints

### Cambiado

- PK de `documents` pasa de `(document_id)` a `(document_id, issue_date)` — requerido por PostgreSQL para la clave de partición
- UNIQUE constraints incluyen `issue_date` — unicidad práctica se mantiene por diseño de access_key/idempotency_key
- FK de `webhook_deliveries` → `documents` eliminada — integridad referencial se mantiene en capa de aplicación

## [0.23.0] - 2026-04-10

### Agregado

- **PgBouncer como connection pooler** (T-069): configuración de PgBouncer en modo `transaction` para gestión eficiente de conexiones PostgreSQL con múltiples tenants
- Archivos de configuración `docker/pgbouncer/pgbouncer.ini` y `docker/pgbouncer/userlist.txt`
- Servicio `pgbouncer` en `docker-compose.yml` (imagen `edoburu/pgbouncer:1.23.1-p2`)
- Servicio `postgres` des-comentado en `docker-compose.yml`
- Sección "PgBouncer como Connection Pooler" en `DEPLOYMENT.md`

### Cambiado

- `TenantSchemaResolver.buildSearchPathSql()` ahora genera `SET LOCAL search_path` en lugar de `SET search_path` — compatible con PgBouncer modo `transaction` (se resetea automáticamente al finalizar la transacción)
- Removido `new-connection-sql=SET application_name` de `application.properties` (incompatible con PgBouncer modo `transaction`)
- Corregida sección "Gestión de Conexiones PostgreSQL por Tenant" en `ARCHITECTURE.md` — reflejaba Vert.x Reactive PgPool pero la implementación real es JDBC/Agroal/JPA
- Tests de aislamiento de tenant actualizados para usar transacciones explícitas con `SET LOCAL`
- Test nuevo: verifica que `SET LOCAL search_path` se resetea tras commit (compatibilidad PgBouncer)

## [0.22.3] - 2026-04-10

### Agregado

- **Backpressure y monitoreo de profundidad de cola** (T-068): `QueueDepthHealthCheck` (`@Readiness`) marca instancia como DOWN si alguna cola RabbitMQ supera el umbral crítico
- `QueueDepthMetrics`: métricas Micrometer `key49.queue.depth{queue=sign|send|authorize|notify|dlq}` actualizadas cada 30 segundos vía API de management de RabbitMQ
- Variables de entorno `KEY49_QUEUE_DEPTH_CRITICAL` (defecto 5000) y `KEY49_QUEUE_DEPTH_WARNING` (defecto 1000)
- `DlqAlertRule.extractMessageCount()` ahora es `public` para reutilizarse desde otros paquetes
- 16 tests unitarios para health check y métricas de profundidad de cola

## [0.22.2] - 2026-04-10

### Agregado

- **Graceful shutdown con drenaje de consumers** (T-067): `GracefulShutdownObserver` observa `ShutdownEvent` y reporta mensajes en vuelo por consumer antes del apagado
- `InFlightTracker`: rastrea mensajes in-flight por consumer (`SignConsumer`, `SendConsumer`, `AuthorizeConsumer`, `NotifyConsumer`, `DlqConsumer`)
- Los 5 consumers ahora registran inicio/fin de procesamiento en `InFlightTracker` vía try-finally
- Sección "Despliegue sin pérdida de mensajes" en `DEPLOYMENT.md` con procedimiento paso a paso

### Verificado

- `quarkus.shutdown.timeout=30s` permite que consumers en vuelo terminen antes del shutdown
- RabbitMQ re-encola automáticamente mensajes no-acked al cerrar la conexión (`basic.nack` con requeue)

## [0.22.1] - 2026-04-10

### Agregado

- **Timeouts y Circuit Breaker para MinIO** (T-066): `ObjectStorageService` ahora configura timeouts en `MinioClient` vía `setTimeout(connect, write, read)`. Variables: `KEY49_STORAGE_CONNECT_TIMEOUT_S` (5s), `KEY49_STORAGE_WRITE_TIMEOUT_S` (30s), `KEY49_STORAGE_READ_TIMEOUT_S` (15s)
- `@CircuitBreaker` en `ObjectStorageService.store()` y `ObjectStorageService.retrieve()` con parámetros `requestVolumeThreshold=10, failureRatio=0.5, delay=30s, successThreshold=3`. Si MinIO cae, los consumers fallan rápido y van a retry sin bloquear threads
- `StorageExceptionMapper`: mapea `StorageException` y `CircuitBreakerOpenException` a HTTP 503 Service Unavailable con formato de error estándar
- `ObjectStorageServiceCircuitBreakerTest`: 2 tests de integración — apertura del circuito para `store()` y `retrieve()`
- `StorageExceptionMapperTest`: 5 tests unitarios para mapeo de excepciones
- 2 tests de configuración de timeouts en `ObjectStorageServiceTest`

## [0.22.0] - 2026-04-10

### Agregado

- **Circuit Breaker para SRI SOAP** (T-065): `@CircuitBreaker` y `@Timeout` en `SriReceptionClient` y `SriAuthorizationClient` con parámetros `requestVolumeThreshold=10, failureRatio=0.5, delay=30s, successThreshold=3`. Timeouts: recepción 3s, autorización 5s
- `SriEndpoints` refactorizado de clase estática a bean CDI `@ApplicationScoped` con URLs configurables vía `KEY49_SRI_URL_TEST_RECEPTION`, `KEY49_SRI_URL_TEST_AUTHORIZATION`, `KEY49_SRI_URL_PRODUCTION_RECEPTION`, `KEY49_SRI_URL_PRODUCTION_AUTHORIZATION`
- `SendConsumer` y `AuthorizeConsumer`: captura explícita de `CircuitBreakerOpenException` y `TimeoutException` — los documentos van a RETRY (no FAILED) cuando el circuit breaker está abierto o hay timeout de Fault Tolerance
- `SriReceptionHealthCheck` y `SriAuthorizationHealthCheck` migrados a inyección CDI de `SriEndpoints`
- `MockSriServerResource`: recurso de test con mock HTTP server para simular respuestas del SRI
- `SriReceptionCircuitBreakerTest`: 2 tests de integración — apertura del circuito tras 10 fallos, recuperación tras delay
- Métricas Fault Tolerance exportadas automáticamente a Prometheus: `ft.circuitbreaker.calls.total`, `ft.circuitbreaker.state.total`, `ft.circuitbreaker.opened.total`

### Corregido

- Bug crítico: `CircuitBreakerOpenException` y `TimeoutException` (MicroProfile FT) no eran capturadas por los consumers, causando transición incorrecta a FAILED en lugar de RETRY

## [0.21.2] - 2026-04-10

### Agregado

- **Caché de certificados .p12 en memoria** (T-064): `CertificateCacheService` con `ConcurrentHashMap` que cachea `PrivateKey + X509Certificate + chain` ya parseados por `tenant_id`. TTL configurable vía `KEY49_CERT_CACHE_TTL_MINUTES` (default 30 min), máximo de entradas vía `KEY49_CERT_CACHE_MAX_ENTRIES` (default 100)
- `XAdESBESSigner.sign(xml, CertificateData)`: overload que acepta datos de certificado ya parseados (cache-friendly)
- `XAdESBESSigner.CertificateData` y `loadCertificateData()` ahora son públicos para uso externo
- `SignConsumer` refactorizado: delega descifrado AES + parsing PKCS12 a `CertificateCacheService.getOrLoad()`, eliminando ~50ms por firma repetida
- Invalidación automática en `TenantAdminService.uploadCertificate()`: limpia caché de certificados + caché Redis de tenant
- Limpieza de datos sensibles: bytes descifrados del .p12 y contraseña se limpian de memoria tras el parsing
- `CertificateCacheServiceTest`: 8 tests de integración — cache miss/hit, múltiples tenants, invalidación, re-carga, firma consecutiva con cache
- Documentación en DEPLOYMENT.md: sección "Caché de Certificados .p12 en Memoria"

## [0.21.1] - 2026-04-10

### Agregado

- **Caché de metadatos de tenant en Redis** (T-063): `TenantCacheService` con cache Redis (hash `key49:tenant:{uuid}` + índice `key49:tenant:schema:{name}`) y TTL configurable vía `KEY49_TENANT_CACHE_TTL_SECONDS` (default 600s = 10 min). Excluye certificado binario del cache
- Consumers `SendConsumer`, `AuthorizeConsumer`, `NotifyConsumer` y `ConsumerErrorHandler` migrados de `TenantRepository.findBySchemaName()` a `TenantCacheService.findBySchemaName()`
- `MetricsService` migrado de `TenantRepository.findById()` a `TenantCacheService.findById()`
- Invalidación automática en `TenantAdminService.update()` y `uploadCertificate()`
- Fallback graceful a BD si Redis no está disponible
- `TenantCacheServiceTest`: 6 tests de integración — serialización, deserialización, invalidación, TTL, campos nullable, re-populate
- Documentación en DEPLOYMENT.md: sección "Caché de Metadatos de Tenant en Redis"

## [0.21.0] - 2026-04-10

### Agregado

- **Caché de API keys en Redis** (T-062): `ApiKeyCacheService` con caché Redis (hash `key49:apikey:{hash}`) y TTL configurable vía `KEY49_API_KEY_CACHE_TTL_SECONDS` (default 300s). Cache miss consulta BD y popula Redis. Fallback graceful a BD si Redis no está disponible
- `ApiKeyAuthFilter` refactorizado para usar `ApiKeyCacheService.lookup()` en lugar de SQL directo por request
- Invalidación automática en `ApiKeyManagementService.revoke()`: elimina la entrada de Redis al revocar una key
- `ApiKeyCacheServiceTest`: 6 tests de integración — cache miss + populate, cache hit, invalidación, key inexistente, TTL, re-populate post-invalidación
- Documentación en DEPLOYMENT.md: sección "Caché de API Keys en Redis" con security considerations y variable `KEY49_API_KEY_CACHE_TTL_SECONDS`

## [0.20.3] - 2026-04-10

### Agregado

- **Outbox Poller optimizado para alto throughput** (T-061): batch-size configurable vía `KEY49_OUTBOX_BATCH_SIZE` (default 50), `SELECT ... FOR UPDATE SKIP LOCKED` para concurrencia segura multi-instancia, métricas Micrometer (`key49.outbox.events.polled` counter, `key49.outbox.poll.duration` timer), polling adaptativo con flag `lastCycleHadEvents`
- `OutboxRepository.findUnpublishedForUpdate()`: nuevo método con native query y `FOR UPDATE SKIP LOCKED`
- `OutboxPollerConfigTest`: 5 tests verificando batch-size, poll-interval, métricas registradas e inyección del poller
- Documentación en DEPLOYMENT.md: sección Outbox Poller con métricas y recomendaciones de tuning

## [0.20.2] - 2026-04-10

### Agregado

- **Prefetch diferenciado para consumers RabbitMQ** (T-060): configuración `rabbitmq-prefetch` por canal — sign=10, send=5, authorize=5, notify=10, dlq=5. Valores parametrizados con variables de entorno (`KEY49_RABBITMQ_PREFETCH_SIGN`, `_SEND`, `_AUTHORIZE`, `_NOTIFY`, `_DLQ`). Documentación en DEPLOYMENT.md con tabla de justificación y recomendaciones de tuning
- `RabbitMqPrefetchConfigTest`: 7 tests verificando valores por defecto, relación entre prefetch de consumers SRI vs CPU-bound, y positividad de todos los valores

## [0.20.1] - 2026-04-10

### Agregado

- **Configuración de thread pool** (T-059): `quarkus.thread-pool.max-threads=50` parametrizado con `KEY49_THREAD_POOL_MAX`. Event loops de Vert.x en auto-configuración (2 × cores). Documentación en DEPLOYMENT.md con tabla de dimensionamiento y métricas a monitorear
- `ThreadPoolConfigTest`: 4 tests de integración — virtual threads habilitados, pool configurado, 20 requests HTTP concurrentes sin starvation, 20 accesos BD concurrentes sin bloqueo

### Corregido

- **BouncyCastle duplicado en pom.xml**: eliminada property `bouncycastle.version=1.80` y versiones explícitas de `bcprov-jdk18on`/`bcpkix-jdk18on`. Ahora gestionadas por el BOM de Quarkus 3.34 (1.83), resolviendo inconsistencia con `bcutil-jdk18on` transitivo

## [0.20.0] - 2026-04-10

### Agregado

- **Pool de conexiones PostgreSQL** (T-058): configuración completa de Agroal con `min-size=5`, `max-size=50`, `acquisition-timeout=5s`, `idle-removal-interval=2m`, `max-lifetime=30m`, `validation-query-sql=SELECT 1` y `new-connection-sql=SET application_name = 'key49'`. Todas las propiedades parametrizadas con variables de entorno (`KEY49_DB_POOL_MIN`, `KEY49_DB_POOL_MAX`, etc.)
- `DatasourcePoolHealthCheck`: health check de readiness que verifica conectividad al pool y reporta métricas Agroal (max_size, min_size, active_count, available_count, awaiting_count)
- `DatasourcePoolHealthCheckTest`: 5 tests de integración verificando health check UP, métricas del pool, configuración de tamaño, awaiting en idle y acquisition-timeout

## [0.19.1] - 2026-04-09

### Corregido

- **Portal: descarga de XML/RIDE** — los botones "XML Autorizado" y "RIDE (PDF)" en la vista de detalle apuntaban a endpoints de la API REST (`/v1/invoices/:id/xml`) que requieren `Authorization` header. Ahora apuntan a endpoints propios del portal (`/portal/documents/:id/xml`, `/portal/documents/:id/ride`) que usan autenticación por sesión/cookie
- **NotifyConsumer: email fuera de transacción JTA** — el envío de email se movió fuera de la transacción JTA para evitar rollback del estado NOTIFIED cuando el email tarda más de 60s. Se reemplazó `Mailer` bloqueante por `ReactiveMailer` con timeout configurable (`key49.email.send-timeout-seconds`, default 120s)
- **NotifyConsumer: I/O de RIDE, MinIO y webhook fuera de transacción** — generación de RIDE, almacenamiento en MinIO y despacho de webhook se extrajeron de la transacción JTA. La transacción ahora solo contiene lectura de BD, actualización de rutas y transición de estado, evitando rollbacks por `StorageException` o timeouts de red

### Agregado

- Endpoints `GET /portal/documents/:id/xml` y `GET /portal/documents/:id/ride` en `PortalResource` con autenticación por sesión
- 3 tests en `PortalEndToEndTest`: 404 cuando XML/RIDE no disponible, redirect a login sin sesión
- Propiedad `key49.email.send-timeout-seconds` para controlar el timeout del envío de email

## [0.19.0] - 2026-04-09

### Agregado

- **RideDataMapper** (T-037b): mapper que convierte `Document + Tenant + requestPayload` al record RIDE apropiado según el tipo de documento y genera el PDF invocando el generador correspondiente (factura, nota de crédito, nota de débito, retención, guía de remisión, liquidación de compra)
- **Integración RIDE en NotifyConsumer** (T-037b): paso 1 del flujo de notificación genera el RIDE (PDF) de forma no-bloqueante — fallo en la generación no impide la transición a NOTIFIED
- **Integración MinIO en NotifyConsumer** (T-037c): paso 2 almacena el XML autorizado (`DocumentArtifact.AUTHORIZED_XML`) y el RIDE (`DocumentArtifact.RIDE`) en MinIO, actualizando `doc.authorizedXmlPath` y `doc.ridePath`. Fallo de storage es no-bloqueante
- **Integración email en NotifyConsumer** (T-037d): paso 3 construye `EmailData` y envía email con RIDE PDF y XML autorizado adjuntos. Actualiza `doc.emailSentAt`, `doc.emailStatus` ("SENT"/"FAILED") y `doc.emailError`. Fallo de email es no-bloqueante
- `RideDataMapperTest`: 10 tests unitarios cubriendo los 6 tipos de documento, payloads vacíos/null, JSON inválido y tipo desconocido
- `NotifyConsumerTest`: 12 tests unitarios con mocks verificando flujo completo, fallos parciales no-bloqueantes (RIDE, storage, email), actualización de campos y casos borde

## [0.18.0] - 2026-04-09

### Agregado

- **Smart duplicate handling** (todos los servicios): documentos en estado REJECTED/FAILED se reciclan automáticamente al reenviar con los mismos datos de unicidad (tipo + establecimiento + punto + secuencial), retornando 202. Documentos en estado activo/completado retornan 409 con información del documento existente (id, status, accessKey, authorizationDate)
- `DuplicateDocumentException` + `DuplicateDocumentExceptionMapper`: excepción de negocio y mapper JAX-RS que produce 409 con `error.existingDocument`
- `DocumentStatus.isRetryableTerminal()`: distingue estados terminales reciclables (REJECTED, FAILED) de terminales absolutos (VOIDED)
- Transiciones `REJECTED → CREATED` y `FAILED → CREATED` en la máquina de estados
- Portal: columna "Documento" con nombre legible del tipo en dashboard.html
- Portal: sección de mensajes SRI detallados en detail.html
- Script `test-curls.sh` para pruebas manuales de los 6 tipos de documento electrónico
- Configuración `%test.key49.outbox.poll-interval=9999s`, `%test.key49.retry.poll-interval=9999s` y `%test.key49.master-key` para estabilizar tests

### Corregido

- **CreditNoteDataMapper** (T-053): reescrito con records `RawPayload`/`RawItem`/`RawTax` que coinciden con `CreateCreditNoteRequest`. Cálculo de `subtotalBeforeTax`, `taxableBase`, `amount` y `totalTaxes` agregados
- **PurchaseClearanceDataMapper** (T-054): reescritura completa con records intermedios. Corregido `taxCode` → `code`, campos derivados calculados
- **WaybillDataMapper** (T-055): corregido campo `rise` (null) en `PayloadCarrier`
- **DebitNoteDataMapper** (T-056): validado correcto, sin cambios requeridos
- **WithholdingDataMapper/WithholdingXmlBuilder** (T-057): validado correcto, corrección menor en builder
- **CreditNoteDataMapperTest**: JSON de test alineado con records actuales (eliminados `taxable_base`, `amount`)
- **PurchaseClearanceDataMapperTest**: JSON de test alineado (eliminados `subtotalBeforeTax`, `taxCode`, `totalTaxes`)
- **SignConsumerIntegrationTest**: uso de master key fija configurada en `%test` en vez de `System.setProperty` (no funciona tras arranque de Quarkus)
- **E2E tests**: estabilizados desactivando outbox/retry pollers en perfil test; tests de duplicados actualizados para aceptar 409 o 202
- `test-curls.sh`: tarifa retención código 312 corregida de 1.75% a 2.0% según catálogo SRI

## [0.17.0] - 2026-04-08

### Corregido

- Firma XAdES-BES para SRI (T-037e): `setDefaultNamespacePrefix("ds")` para canonicalización correcta, IDs numéricos aleatorios, solo certificado firmante en X509Data, Id en SignedInfo/Reference, `Element.setIdAttributeNS()` para resolución de URIs, descripción "contenido comprobante". Factura autorizada exitosamente por SRI en ambiente de pruebas
- InvoiceDataMapper (T-037f): reescrito con records `RawPayload`/`RawItem`/`RawTax`/`RawPayment` que coinciden con la estructura real del JSON de `CreateInvoiceRequest`. Cálculo de campos derivados (`subtotalBeforeTax`, `taxableBase`, `amount`, `totalTaxes`). Resuelve error [35] ARCHIVO NO CUMPLE ESTRUCTURA XML

## [0.16.13] - 2026-04-08

### Cambiado

- Extraída clase `XmlTestHelper` reutilizable para manipulación de XML en tests de validación XSD (T-052): centraliza `parseXml`, `serialize`, `removeElement`, `replaceElementValue`, `validateAgainstXsd`, `assertXsdFails` y `buildValidXml` (6 tipos de comprobante). Refactorizadas las 8 clases de test consumidoras eliminando ~1400 líneas de código duplicado

## [0.16.12] - 2026-04-08

### Agregado

- Tests negativos de `XsdValidator` para todos los tipos de documento (T-051): nested class `InvalidXmlAllTypes` con 24 tests parametrizados (6 tipos × 4 escenarios: XML malformado, XML vacío, elemento raíz incorrecto, nodo principal ausente)

## [0.16.11] - 2026-04-08

### Agregado

- Tests de validación de patterns XSD para restricciones regex del SRI (T-050): clase `XsdPatternValidationTest` con 132 tests parametrizados por tipo de comprobante (6 tipos × 8 campos de `infoTributaria`: RUC, establecimiento, punto de emisión, secuencial, clave de acceso, código de documento, ambiente, tipo de emisión)

## [0.16.10] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en liquidación de compra XSD v1.1.0 (T-049): clase `PurchaseClearanceXsdMandatoryFieldsTest` con 26 tests parametrizados para `infoTributaria`, `infoLiquidacionCompra` y `detalles`

## [0.16.9] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en guía de remisión XSD v1.1.0 (T-048): clase `WaybillXsdMandatoryFieldsTest` con 26 tests parametrizados para `infoTributaria`, `infoGuiaRemision`, `destinatario` y `detalle`

## [0.16.8] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en retención XSD v2.0.0 (T-047): clase `WithholdingXsdMandatoryFieldsTest` con 34 tests parametrizados para `infoTributaria`, `infoCompRetencion`, `docsSustento` y `retencion`

## [0.16.7] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en nota de débito XSD v1.0.0 (T-046): clase `DebitNoteXsdMandatoryFieldsTest` con 24 tests parametrizados para `infoTributaria`, `infoNotaDebito` y `motivos`

## [0.16.6] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en nota de crédito XSD v1.1.0 (T-045): clase `CreditNoteXsdMandatoryFieldsTest` con 28 tests parametrizados para `infoTributaria`, `infoNotaCredito` y `detalles`

## [0.16.5] - 2026-04-08

### Agregado

- Tests negativos de campos obligatorios faltantes en factura XSD v2.1.0 (T-044): clase `InvoiceXsdMandatoryFieldsTest` con 26 tests parametrizados que verifican que el XSD rechaza el XML cuando falta un campo obligatorio en `infoTributaria`, `infoFactura` o `detalles`

### Corregido

- Método duplicado `paymentsIncluded()` en `WithholdingXmlBuilderTest.Pagos` renombrado a `paymentsIncludedInMinimal()`

## [0.16.4] - 2026-04-08

### Agregado

- Tests de validación XSD para `WaybillXmlBuilder` (T-043): guía de remisión simple, múltiples destinatarios y mínima contra `GuiaRemision_V1.1.0.xsd`

## [0.16.3] - 2026-04-08

### Agregado

- Tests de validación XSD para `WithholdingXmlBuilder` (T-042): retención simple, múltiples docs sustento y mínima contra `ComprobanteRetencion_V2.0.0.xsd`

### Corregido

- `WithholdingDataFixtures`: formato de `numDocSustento` corregido a 15 dígitos sin guiones (patrón XSD `[0-9]{15}`)
- `WithholdingDataFixtures`: `minimalSupportingDocument()` ahora incluye pago obligatorio (requerido por XSD)

## [0.16.2] - 2026-04-08

### Agregado

- Tests de validación XSD para `DebitNoteXmlBuilder` (T-041): nota de débito simple, múltiples motivos y mínima contra `NotaDebito_V1.0.0.xsd`

## [0.16.1] - 2026-04-08

### Agregado

- Tests de validación XSD para `CreditNoteXmlBuilder` (T-040): nota de crédito simple, multi-ítem y mínima contra `NotaCredito_V1.1.0.xsd`

## [0.16.0] - 2026-04-08

### Cambiado

- **Refactorización mayor**: consolidación de 10 módulos Maven (key49-api, key49-core, key49-xml, key49-signer, key49-sri, key49-queue, key49-ride, key49-notify, key49-storage, key49-admin) en un solo módulo raíz
  - Todo el código fuente migrado a `src/main/java/` y `src/test/java/` bajo el paquete `auracore.key49.*`
  - Eliminados los 10 subdirectorios de módulos y sus POMs individuales
  - POM raíz convertido de parent multi-módulo a proyecto único con todas las dependencias
- **Migración de Hibernate Reactive a Hibernate ORM clásico (imperativo)**
  - Reemplazado `quarkus-hibernate-reactive-panache` + `quarkus-reactive-pg-client` por `quarkus-hibernate-orm-panache` + `quarkus-jdbc-postgresql`
  - Eliminadas todas las cadenas `Uni<T>` / `Uni.createFrom()` / `.chain()` / `.onItem()` — ahora código imperativo directo
  - `TenantConnectionManager`: `withTenantSession()` y `withTenantTransaction()` ahora usan `EntityManager` + `@Transactional` con `SET search_path` vía native query
  - Consumers RabbitMQ mantienen `@Blocking` (virtual threads)
  - Repositorios Panache ahora extienden `PanacheRepositoryBase` imperativo
- Campos JSONB en entidades: migrados de `@Type(JsonBinaryType.class)` a `@JdbcTypeCode(SqlTypes.JSON)`

### Agregado

- `quarkus-junit5-mockito` como dependencia de test para soporte de `@InjectMock`
- 7 nuevos tests de integración para el pipeline async (35 test methods):
  - `SignConsumerIntegrationTest` — firma XAdES-BES, clave acceso, outbox
  - `SendConsumerIntegrationTest` — SOAP recepción: éxito, negocio, infra, reintentos
  - `AuthorizeConsumerIntegrationTest` — SOAP autorización: éxito, negocio, infra, reintentos
  - `NotifyConsumerIntegrationTest` — webhook dispatch, transición NOTIFIED
  - `DlqConsumerIntegrationTest` — DLQ: RETRY→FAILED, estados terminales
  - `ConsumerErrorHandlerIntegrationTest` — persistencia de errores, transición FAILED
  - `OutboxPollerIntegrationTest` — polling, publicación, marking published
- `TestSchemaHelper` — helper compartido con DDL para tests de integración de consumers
- Tareas T-040 a T-052 en TASKS.md — Fase 5: cobertura de tests XML/XSD

### Corregido

- Todas las entidades, repositorios, servicios, recursos, filtros y consumers convertidos de reactive a imperativo
- 1376 tests pasan (0 fallos, 0 errores) tras la migración completa

## [0.15.1] - 2026-04-08

### Corregido

- Jandex plugin faltante en key49-queue y key49-sri — CDI no descubría beans de estos módulos
- `@WithSession` faltante en `OutboxPoller`, `OutboxCleanup`, `RetryPoller` — Panache requiere sesión fuera de JAX-RS
- `quarkus.index-dependency` para key49-queue y key49-notify — SmallRye Messaging no detectaba `@Incoming`/`@Outgoing`
- `Document.lastErrorMessage` — `columnDefinition = "text"` para coincidir con schema SQL (era varchar 255)
- `DocumentRepository.findRetryReady()` — reemplazar `now()` HQL por parámetro `Instant.now()` (incompatibilidad de tipos)
- Consumers RabbitMQ: aceptar `JsonObject` en lugar de `DocumentEvent` — SmallRye RabbitMQ no auto-deserializa POJOs
- `DocumentEvent.fromJson()` — usar snake_case (`document_id`, `tenant_schema_name`) por config global `SNAKE_CASE` de Jackson
- `@WithSession` en los 5 consumers de RabbitMQ (Sign, Send, Authorize, Notify, DLQ)
- `InvoiceXmlBuilder.appendDecimalElement()` — null-safe para `BigDecimal` opcionales (discount)

### Agregado

- `ConsumerErrorHandler` — servicio que persiste errores inesperados de consumers en BD (`last_error_message`, transición a FAILED)
- Los 5 consumers ahora registran errores en BD en lugar de solo loguearlos

## [0.15.0] - 2026-04-07

### Agregado

- Alertas operativas con 5 reglas y notificación email/webhook (T-037)
  - `AlertResult`, `AlertState`, `AlertRule` — modelo de alertas inmutable
  - `AlertStateRepository` — persistencia de estado de alertas en Redis (HSET/HGETALL, TTL 7 días)
  - `AlertNotifier` — notificaciones por email (ReactiveMailer) y webhook (HMAC-SHA256)
  - `SriHealthAlertRule` — alerta cuando SRI no responde > 5 minutos (reutiliza health checks)
  - `DlqAlertRule` — alerta cuando DLQ tiene mensajes (RabbitMQ Management API)
  - `CertificateExpiryAlertRule` — alerta cuando certificados vencen en < 30 días
  - `ErrorRateAlertRule` — alerta cuando tasa de error > 5% (Micrometer counters)
  - `QueueDepthAlertRule` — alerta cuando colas de procesamiento > 1000 mensajes
  - `AlertEvaluator` — orquestador con evaluación cada 60s (infra) y 6h (certificados)
  - Transiciones de estado OK→FIRING→RESOLVED con reminders cada 4 horas
  - 23 tests unitarios para modelo, reglas y evaluador
  - 14 propiedades de configuración con valores por defecto sensatos

## [0.14.0] - 2026-04-07

### Agregado

- Guía para desarrolladores (T-036)
  - `docs/DEVELOPER-GUIDE.md` — documentación completa para integración de clientes
  - Quickstart en 4 pasos con verificación de conexión y primera factura
  - Referencia de campos para los 6 tipos de documento: Factura, Nota de Crédito, Nota de Débito, Guía de Remisión, Comprobante de Retención, Liquidación de Compra
  - Ejemplos curl para cada tipo de documento
  - Ejemplos de integración en Python, Node.js y Java
  - Catálogo de errores con códigos, descripciones y acciones
  - Secciones de idempotencia, rate limiting, webhooks, anulación, descarga XML/RIDE

## [0.13.0] - 2026-04-07

### Agregado

- Tests end-to-end para todos los tipos de documento (T-035)
  - `CreditNoteEndToEndTest` — 25 tests E2E para notas de crédito
  - `DebitNoteEndToEndTest` — 25 tests E2E para notas de débito
  - `WaybillEndToEndTest` — 24 tests E2E para guías de remisión
  - `WithholdingEndToEndTest` — 24 tests E2E para comprobantes de retención
  - `PurchaseClearanceEndToEndTest` — 25 tests E2E para liquidaciones de compra
  - Cada test cubre: creación, idempotencia, consulta por ID, listado con filtros, duplicados, validaciones, XML/RIDE no disponible, anulación, paginación, resend-email y outbox

### Corregido

- `WaybillService` y `WithholdingService` no asignaban `createdAt`/`updatedAt` al persistir documentos
- `WaybillService` y `WithholdingService` creaban `OutboxEvent` antes de `session.persist(doc)`, causando `aggregate_id` nulo
- Rate limit en tests: aumentado `rate_limit_rpm` a 10000 en todos los tests E2E para evitar 429 por bucket compartido

## [0.12.0] - 2026-04-07

### Agregado

- RIDE templates para todos los tipos de documento (T-034)
  - `PurchaseClearanceRideData`, `PurchaseClearanceRideGenerator` — generación RIDE (PDF) para liquidación de compra con datos de proveedor, ítems, impuestos, pagos, totales, QR y marca de agua
  - 8 tests unitarios para PurchaseClearanceRideGenerator
  - Los 6 tipos de documento ahora tienen RIDE completo: Factura, Nota de Crédito, Nota de Débito, Guía de Remisión, Comprobante de Retención, Liquidación de Compra

## [0.11.0] - 2026-04-07

### Agregado

- Liquidación de Compra — tipo 03, XSD v1.1.0 (T-033)
  - `PurchaseClearanceData`, `PurchaseClearanceXmlBuilder` — generación XML con datos de proveedor, ítems, impuestos, pagos
  - `CreatePurchaseClearanceRequest`, `PurchaseClearanceResponse` — DTOs de API REST
  - `PurchaseClearanceService` — validación de proveedor (tipos ID 04-08), ítems con impuestos, pagos, emisión mismo día
  - `PurchaseClearanceResource` — endpoints REST: POST/GET `/v1/purchase-clearances`, XML, RIDE, void, resend-email
  - `PurchaseClearanceDataMapper` — mapeo Document→PurchaseClearanceData para firma
  - `SignConsumer` actualizado con caso PURCHASE_CLEARANCE
  - 62 tests unitarios (XmlBuilder, Service, DataMapper)

## [0.10.0] - 2026-04-06

### Agregado

- Guía de Remisión — tipo 06, XSD v1.1.0 (T-032)
  - `WaybillData`, `WaybillXmlBuilder` — generación XML con destinatarios, ítems, datos de transportista
  - `CreateWaybillRequest`, `WaybillResponse` — DTOs de API REST
  - `WaybillService` — validación de transportista (tipos ID 04-08), fechas de transporte, placa, destinatarios con ítems
  - `WaybillResource` — endpoints REST: POST/GET `/v1/waybills`, XML, RIDE, void, resend-email
  - `WaybillDataMapper` — mapeo Document→WaybillData para firma
  - `WaybillRideData`, `WaybillRideGenerator` — generación RIDE PDF con secciones por destinatario
  - `SignConsumer` actualizado con caso WAYBILL
  - 52 tests unitarios (XmlBuilder, DataMapper, RideGenerator)

## [0.9.0] - 2026-04-06

### Agregado

- Comprobante de Retención ATS — tipo 07, XSD v2.0.0 (T-031)
  - `WithholdingData`, `WithholdingXmlBuilder` — generación XML con soporte completo de `docsSustento` (impuestos, retenciones, pagos)
  - `CreateWithholdingRequest`, `WithholdingResponse` — DTOs de API REST
  - `WithholdingService` — validación de sujeto retenido, periodo fiscal (MM/yyyy), documentos de sustento, códigos de retención (1=Renta, 2=IVA, 6=ISD)
  - `WithholdingResource` — endpoints REST: POST/GET `/v1/withholdings`, XML, RIDE, void, resend-email
  - `WithholdingDataMapper` — mapeo Document→WithholdingData para firma
  - `WithholdingRideData`, `WithholdingRideGenerator` — generación RIDE PDF con tabla de retenciones
  - `SignConsumer` actualizado con caso WITHHOLDING
  - 46 tests unitarios (XmlBuilder, DataMapper, RideGenerator)

## [0.8.0] - 2026-04-06

### Agregado

- Nota de Débito — tipo 05, XSD v1.0.0 (T-030)
  - `DebitNoteData`: record con estructura de datos para generación XML de nota de débito
  - `DebitNoteXmlBuilder`: genera `<notaDebito>` conforme a XSD SRI v1.0.0
  - `CreateDebitNoteRequest` / `DebitNoteResponse`: DTOs de API REST
  - `DebitNoteService`: lógica de negocio (crear, anular, listar, reenviar email)
  - `DebitNoteResource`: endpoints REST en `/v1/debit-notes` (CRUD + xml + ride + void + resend)
  - `DebitNoteDataMapper`: mapeo Document + Tenant → DebitNoteData para firma en cola
  - `SignConsumer`: soporte de nota de débito en despachador XML por tipo de documento
  - `DebitNoteRideData` / `DebitNoteRideGenerator`: generación RIDE (PDF) de notas de débito
  - Sección `<motivos>` con razón y valor (sin tabla de detalles por ítem)
  - Impuestos a nivel de documento con campo `tarifa` requerido
  - Pagos opcionales en `<infoNotaDebito>`
  - Tests: DebitNoteXmlBuilderTest, DebitNoteServiceTest, DebitNoteRideGeneratorTest, DebitNoteDataMapperTest
- Nota de Crédito — tipo 04, XSD v1.1.0 (T-029)
  - `CreditNoteData`: record con estructura de datos para generación XML de nota de crédito
  - `CreditNoteXmlBuilder`: genera `<notaCredito>` conforme a XSD SRI v1.1.0
  - `CreateCreditNoteRequest` / `CreditNoteResponse`: DTOs de API REST
  - `CreditNoteService`: lógica de negocio (crear, anular, listar, reenviar email)
  - `CreditNoteResource`: endpoints REST en `/v1/credit-notes` (CRUD + xml + ride + void + resend)
  - `CreditNoteDataMapper`: mapeo Document + Tenant → CreditNoteData para firma en cola
  - `SignConsumer`: despachador XML por tipo de documento (factura / nota de crédito)
  - `CreditNoteRideData` / `CreditNoteRideGenerator`: generación RIDE (PDF) de notas de crédito
  - Campos modificados: modifiedDocumentCode, modifiedDocumentNumber, modifiedDocumentDate, reason
  - Tests: CreditNoteXmlBuilderTest, CreditNoteServiceTest, CreditNoteRideGeneratorTest, CreditNoteDataMapperTest
- Configuración de producción (T-028)
  - `SecurityHeadersFilter`: headers de seguridad OWASP en todas las respuestas JAX-RS
    - X-Frame-Options: DENY, X-Content-Type-Options: nosniff, Referrer-Policy, Permissions-Policy, CSP
  - CORS por perfil: dev/test permisivo (`/.*/`), producción restringido (orígenes, métodos, headers)
  - HSTS para producción: `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - Graceful shutdown: `quarkus.shutdown.timeout=30s`
  - Dockerfile multi-stage (JVM): build con Maven + runtime con Eclipse Temurin 25 JRE Alpine
  - Usuario no-root (`key49`) en contenedor
  - Eliminación de `quarkus.http.cors=true` deprecado (Quarkus 3.x auto-detecta por `cors.origins`)
  - 6 tests nuevos (896 total proyecto, 0 failures)

## [0.7.0] - 2026-04-06

### Agregado

- Portal web de consulta para tenants (T-028a)
  - Server-side rendering con Qute + Pico CSS v2 + HTMX v2.0.4
  - Autenticación por API key con sesiones Redis (cookie HttpOnly, TTL 30min)
  - PortalAuthFilter (priority 15) valida sesión en todas las rutas /portal/\*
  - Pantalla login: formulario con API key, mensajes de error
  - Pantalla dashboard: tabla de documentos con filtros (estado, fechas, búsqueda) + paginación
  - Pantalla detalle: datos del documento, receptor, totales, timeline de procesamiento
  - Polling automático de estado con HTMX (hx-trigger="every 5s") para documentos en proceso
  - Endpoint parcial GET /portal/documents/{id}/status para actualizaciones HTMX
  - Portal solo lectura — no permite crear ni modificar documentos
  - 10 tests E2E
- Gestión de tenants para administración (T-021)
  - `CertificateMetadataExtractor`: extracción de metadata (subject, serial, expiración, issuer) de certificados .p12
  - `TenantAdminService`: CRUD de tenants en esquema público con validación de RUC y unicidad de schema_name
  - REST API `/v1/admin/tenants`: crear, listar (paginación + filtro por status), consultar, actualizar
  - Upload de certificado .p12 (`POST /v1/admin/tenants/:id/certificate`): validación, extracción de metadata, cifrado AES-256-GCM
  - Consulta de estado de certificado (`GET /v1/admin/tenants/:id/certificate/status`): subject, serial, expiración, días restantes
  - DTOs: `CreateTenantRequest`, `UpdateTenantRequest`, `TenantResponse` (con `CertificateSummary`), `CertificateStatusResponse`
  - `TenantExceptionMapper` para errores de negocio de tenants
  - 50 tests nuevos (768 total proyecto, 0 failures)
- Endpoints de perfil de tenant autenticado (T-022)
  - `TenantProfileResource`: endpoints en `/v1/tenant` para self-service del tenant
  - `GET /v1/tenant/profile`: obtener datos del tenant autenticado vía API key
  - `PUT /v1/tenant/profile`: actualizar datos propios (razón social, dirección, webhook, email)
  - `POST /v1/tenant/certificate`: subir certificado .p12 con validación y cifrado
  - `GET /v1/tenant/certificate/status`: verificar vigencia del certificado
  - `UpdateProfileRequest`: DTO restringido sin campos administrativos (status, rateLimitRpm)
  - Integración con `TenantContext` para resolver tenant desde API key
  - 16 tests nuevos (784 total proyecto, 0 failures)
- Gestión de API keys para tenants (T-023)
  - `ApiKeyManagementService`: CRUD de API keys (creación, listado, consulta, revocación)
  - Validación de datos: nombre requerido (max 100 chars), environment (test/production)
  - `POST /v1/tenant/api-keys`: crear nueva API key (devuelve rawKey solo una vez)
  - `GET /v1/tenant/api-keys`: listar API keys del tenant autenticado
  - `GET /v1/tenant/api-keys/:id`: consultar API key por ID con verificación de ownership
  - `DELETE /v1/tenant/api-keys/:id`: revocar API key (409 si ya revocada)
  - DTOs: `CreateApiKeyRequest`, `ApiKeyResponse` (fromEntity/fromCreated con rawKey condicional)
  - `ApiKeyExceptionMapper`: mapeo de errores a respuestas JSON con code/message
  - `@JsonInclude(NON_NULL)` para omitir rawKey en respuestas de listado/consulta
  - 34 tests nuevos (818 total proyecto, 0 failures)
- Dashboard de métricas para tenant (T-025)
  - `MetricsService`: consulta de documentos agrupados por estado con HQL en esquema tenant
  - `GET /v1/metrics/summary`: resumen de hoy, mes actual, certificado y última factura
  - `MetricsSummaryResponse`: DTO con `PeriodSnapshot` (total, authorized, rejected, pending, failed)
  - Clasificación de estados: AUTHORIZED+NOTIFIED+VOIDED → authorized; REJECTED → rejected; FAILED → failed; resto → pending
  - Días restantes de certificado calculados desde `certificate_expiration` del tenant
  - `lastInvoiceAt`: fecha de última autorización (`MAX(authorization_date)`)
  - Queries secuenciales para compatibilidad con Hibernate Reactive (una sesión, sin paralelismo)
  - 20 tests nuevos (838 total proyecto, 0 failures)
- Rate limiting con Redis (T-026)
  - `RateLimiter`: sliding window con Redis sorted sets y Lua script atómico (ZREMRANGEBYSCORE + ZADD + ZCARD + PEXPIRE)
  - `RateLimitFilter`: `@ServerRequestFilter(priority=20)` que aplica rate limit después de autenticación
  - `@ServerResponseFilter` para inyectar headers `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
  - Respuesta 429 con `Retry-After` y error code `RATE_LIMIT_EXCEEDED` cuando se excede el límite
  - Configuración por tenant vía campo `rate_limit_rpm` en tabla `tenants` (default 100 RPM)
  - Clave Redis: `ratelimit:{api_key_prefix}`, ventana deslizante de 60 segundos
  - Modo permisivo si Redis no disponible (permite request, log warning)
  - `TenantContext` extendido con `rateLimitRpm` y `apiKeyPrefix`
  - `ApiKeyAuthFilter` actualizado: priority=10, consulta `rate_limit_rpm`, pobla TenantContext
  - 16 tests nuevos (168 total proyecto, 0 failures)
- Endpoint XML Raw para documentos pre-armados (T-028b)
  - `POST /v1/documents/raw`: recibe XML completo con `Content-Type: application/xml`
  - Header `X-Document-Type` obligatorio para indicar tipo de comprobante (01-07)
  - Validación XSD dinámica según tipo de documento
  - Validación `<codDoc>` en XML coincide con header `X-Document-Type`
  - Extracción de metadatos: receptor (varía por tipo), totales, serie, email de infoAdicional
  - Clave de acceso siempre generada por Key49, reemplaza cualquier `<claveAcceso>` del XML
  - RUC extraído del XML (`<ruc>` en `<infoTributaria>`)
  - Prevención XXE: deshabilitación de DOCTYPE y entidades externas
  - Documento persiste con `request_origin = 'XML_RAW'` y `original_xml` almacena XML final
  - `GET /v1/documents/raw/:id`: consultar estado de documento
  - `RawDocumentResponse`: DTO con campo `origin` para diferenciar de documentos JSON
  - Idempotencia con `X-Idempotency-Key`, unicidad por serie, duplicados (409)
  - Continúa pipeline normal: firmar → enviar → autorizar → notificar
  - Errores: MISSING_DOCUMENT_TYPE, INVALID_XML_STRUCTURE, DOCUMENT_TYPE_MISMATCH, XSD_VALIDATION_FAILED
  - 10 tests E2E nuevos (186 total proyecto, 0 failures)
- Health checks y observabilidad (T-027)
  - `MinioHealthCheck` (@Readiness): verifica accesibilidad del bucket en MinIO
  - `SriReceptionHealthCheck` (@Liveness): HTTP HEAD al WSDL de Recepción del SRI
  - `SriAuthorizationHealthCheck` (@Liveness): HTTP HEAD al WSDL de Autorización del SRI
  - `CertificateExpirationHealthCheck` (@Readiness): detecta certificados que vencen en < 30 días
  - `CertificateExpirationNotifier`: job diario (08:00 ECT) que envía email y webhook `certificate.expiring`
  - `TracingFilter`: genera `X-Request-Id` (req\_{uuid}) y `X-Trace-Id` (OpenTelemetry) en todas las respuestas
  - OpenTelemetry configurado: tracing automático HTTP, JDBC, RabbitMQ
  - Logs con traceId/spanId en formato de consola (desarrollo)
  - Producción: exportar a Grafana Tempo vía OTLP (`KEY49_OTEL_TRACES_EXPORTER=otlp`)
  - Jandex indexing para key49-admin (descubrimiento de beans CDI)
  - 15 tests nuevos (176 total proyecto, 0 failures)

## [0.6.0] - 2026-04-05

### Agregado

- Generador de RIDE (PDF) para factura electrónica (T-015)
  - `InvoiceRideGenerator`: generación programática con OpenPDF 2.0.3
  - Secciones: encabezado emisor, datos receptor, tabla de ítems, impuestos, totales, pagos, info adicional
  - `QrCodeGenerator`: código QR con clave de acceso (ZXing 3.5.3)
  - Logo del emisor opcional (PNG/JPEG)
  - Marca de agua "SIN AUTORIZACIÓN" para documentos no autorizados
  - `RideData`: record con datos de presentación para el RIDE
  - `RideGenerationException`: excepción específica del módulo
  - 31 tests nuevos (562 total proyecto, 0 failures)
- Almacenamiento de artefactos en MinIO/S3 (T-016)
  - `ObjectStorageService`: almacenamiento y descarga con MinIO Java SDK 8.5.17
  - `StoragePath`: construcción de rutas `{tenant}/{year}/{month}/{docType}/{accessKey}/{filename}`
  - `DocumentArtifact`: enum con 4 tipos (unsigned.xml, signed.xml, authorized.xml, ride.pdf)
  - `StorageException`: excepción específica del módulo
  - Configuración: endpoint, access-key, secret-key, bucket, region con env vars
  - 31 tests nuevos (593 total proyecto, 0 failures)
- Servicio de email para entrega de comprobantes (T-017)
  - `EmailService`: envío reactivo con Quarkus Mailer + Qute template
  - `EmailData`: record con datos de emisor, receptor, adjuntos y parseEmails()
  - Template HTML responsive para email de entrega (document-delivery.html)
  - Soporte múltiples destinatarios separados por `;` (primer email TO, resto CC)
  - Adjuntos opcionales: RIDE (PDF) y XML autorizado
  - Configuración SMTP con env vars, flag `key49.email.enabled` para desactivar
  - 23 tests nuevos (616 total proyecto, 0 failures)
- Endpoints REST de Factura (T-018)
  - POST /v1/invoices — crear factura con validación completa y cola de procesamiento
  - GET /v1/invoices/:id — consultar factura con detalle completo
  - GET /v1/invoices — listar con filtros (status, fechas, recipient_id, access_key, document_type), paginación y ordenamiento
  - GET /v1/invoices/:id/xml — descargar XML desde MinIO
  - GET /v1/invoices/:id/ride — descargar RIDE (PDF) desde MinIO
  - POST /v1/invoices/:id/resend-email — reenviar email vía outbox
  - POST /v1/invoices/:id/void — anulación local (valida estado, plazo día 7, consumidor final)
  - DTOs: CreateInvoiceRequest, InvoiceResponse (summary/detail), ApiResponse, PagedResponse, VoidRequest
  - BusinessException con detalle de campos y BusinessExceptionMapper
  - InvoiceService: validación de frontera, cálculo de totales, idempotencia, unicidad
  - Jackson configurado: SNAKE_CASE, sin timestamps para fechas, CORS habilitado
  - Jandex indexing para key49-storage (descubrimiento de beans CDI)
  - 44 tests nuevos (660 total proyecto, 0 failures)
- Webhooks con firma HMAC-SHA256 (T-019)
  - `WebhookDispatcher`: envío HTTP POST con firma HMAC-SHA256 en header `X-Key49-Signature`
  - `WebhookPayload`: record con 12 campos del evento (documentId, accessKey, status, etc.)
  - `WebhookDelivery`: entidad JPA para registro de entregas en `webhook_deliveries`
  - `WebhookDeliveryRepository`: consultas de reintentos pendientes y por documento
  - Reintentos con backoff: 10s, 60s, 300s (3 intentos máximo)
  - Headers: `X-Key49-Signature` (sha256=hex), `X-Key49-Event`, `X-Key49-Delivery`
  - `NotifyConsumer` integrado: despacha webhook al tenant tras autorización
  - Dependencia `quarkus-jackson` en key49-notify para serialización JSON
  - Jandex indexing para key49-notify (descubrimiento de beans CDI)
  - Configuración: connect-timeout-ms, read-timeout-ms, enabled (flag on/off)
  - 33 tests nuevos (693 total proyecto, 0 failures)
- Test end-to-end de factura electrónica (T-020)
  - `InvoiceEndToEndTest`: 25 tests ordenados ejercitando el flujo completo vía REST API
  - Creación de factura (202), idempotencia, consulta por ID, listado con filtros
  - Filtros: status, rango de fechas, recipient_id
  - Documento duplicado (409), validaciones de request (400): establishment, recipient, items, fecha, pago
  - XML y RIDE no disponibles para documento recién creado (404)
  - Anulación: rechazo en CREATED (409), éxito en AUTHORIZED (200), sin razón (400)
  - Autenticación requerida (401), paginación, rechazo resend-email en CREATED (409)
  - Verificación de eventos outbox (`doc.sign`) y cálculo correcto de totales
  - Fix: `InvoiceResource.downloadXml/downloadRide` convertidos a `Uni<Response>` (corrige error Vert.x EventLoop)
  - Fix: `InvoiceResource.create` cambiado de `io.vertx.mutiny.core.http.HttpServerRequest` a `io.vertx.core.http.HttpServerRequest`
  - 25 tests nuevos (718 total proyecto, 0 failures)

## [0.5.0] - 2026-04-05

### Agregado

- Cliente SOAP de Recepción del SRI (T-011)
  - `SriReceptionClient`: envío de XML firmado (Base64) a `validarComprobante`
  - Circuit breaker (5 fallos, 30s delay) y timeout (8s) con MicroProfile FT
  - `SriReceptionResponseParser`: parser SOAP (RECIBIDA/DEVUELTA)
  - Modelos: `ReceptionStatus`, `SriMessage`, `SriReceptionResponse`
  - `SriEndpoints`: URLs por ambiente (TEST/PRODUCTION)
  - HttpClient nativo con connect timeout 3s, read timeout 5s
  - 40 tests unitarios
- Cliente SOAP de Autorización del SRI (T-012)
  - `SriAuthorizationClient`: consulta por clave de acceso (49 dígitos) a `autorizacionComprobante`
  - Circuit breaker (5 fallos, 30s delay) y timeout (8s) con MicroProfile FT
  - `SriAuthorizationResponseParser`: parser SOAP (AUTORIZADO/NO AUTORIZADO)
  - Extracción de XML autorizado, número y fecha de autorización
  - Modelos: `AuthorizationStatus`, `SriAuthorizationResponse`
  - Validación de longitud de clave de acceso en frontera
  - 40 tests unitarios (total 80 tests SRI)
- Pipeline de procesamiento en colas (T-013)
  - `SignConsumer`: genera XML + clave de acceso + firma XAdES-BES (CREATED → SIGNED)
  - `SendConsumer`: envío SOAP Recepción SRI (SIGNED → SENT → RECEIVED | REJECTED | RETRY)
  - `AuthorizeConsumer`: consulta SOAP Autorización SRI (RECEIVED → AUTHORIZED | REJECTED | RETRY)
  - `NotifyConsumer`: stub transición (AUTHORIZED → NOTIFIED), pendiente T-015/T-016/T-017
  - `DlqConsumer`: handler terminal Dead Letter Queue (→ FAILED)
  - Outbox pattern: `OutboxEvent` entity, `OutboxRepository`, `OutboxPoller` (500ms), `OutboxCleanup` (cron 02:00 ECT), `OutboxEventRouter`
  - `InvoiceDataMapper`: mapeo Document + Tenant + requestPayload JSON → InvoiceData
  - `DocumentEvent.fromOutbox()` factory method
  - `TenantRepository.findAllActive()` para iteración multi-tenant del outbox poller
  - Máquina de estados enforced con `canTransitionTo()` en cada consumer
  - 38 tests nuevos en key49-queue (508 total proyecto, 0 failures)
- Lógica de reintentos con backoff exponencial (T-014)
  - `RetryDelayCalculator`: delays ×3 (5s, 15s, 45s, 135s, 405s)
  - `RetryPoller`: job @Scheduled(5s) que re-encola docs RETRY con `nextRetryAt` vencido
  - Determinación de tipo de reintento por `sriSubmissionDate` (doc.send o doc.authorize)
  - `SendConsumer`/`AuthorizeConsumer`: verificación de agotamiento de reintentos → FAILED
  - Cálculo de `nextRetryAt` con backoff exponencial en cada consumer
  - Transición RETRY → AUTHORIZED agregada al state machine
  - Configuración `key49.retry.poll-interval` en application.properties
  - 22 tests nuevos (531 total proyecto, 0 failures)

## [0.4.0] - 2026-04-05

### Agregado

- Generador de clave de acceso de 49 dígitos (T-006)
  - Algoritmo módulo 11 para dígito verificador
  - Validación de `issue_date` == fecha actual (zona Ecuador)
  - Validación de formatos: establishment (3 díg), issue_point (3 díg), sequence_number (9 díg)
  - 62 tests unitarios
- XML Builder de Factura conforme a XSD v2.1.0 (T-007)
  - Mapeo DTO API → estructura XML SRI (infoTributaria, infoFactura, detalles, pagos)
  - XSDs del SRI incluidos en resources
  - 38 tests unitarios
- Validador XSD dinámico por tipo de documento (T-008)
  - Carga dinámica de XSD por `DocumentType`
  - Captura y mapeo de errores a mensajes legibles
  - Cache concurrente de schemas compilados
  - 27 tests unitarios
- Firma digital XAdES-BES para comprobantes electrónicos (T-009)
  - Firma enveloped XML-DSig con Apache Santuario 4.0.4
  - RSA-SHA1, C14N inclusive, esquema XAdES-BES 1.3.2
  - Carga de certificado .p12 con BouncyCastle 1.80
  - Certificado de pruebas auto-generado
  - 25 tests unitarios
- Cifrado/descifrado AES-256-GCM de certificados (T-010)
  - `CertificateEncryptor`: encrypt/decrypt de bytes y passwords
  - IV aleatorio de 12 bytes por operación, tag de autenticación 128 bits
  - Utilidades: `generateMasterKey`, `decodeMasterKey` (Base64 desde env var)
  - Test integración round-trip: cifrar → descifrar → firmar
  - 28 tests unitarios

## [0.3.0] - 2026-04-05

### Agregado

- Configuración de RabbitMQ con SmallRye Reactive Messaging (T-004)
  - Exchange `key49.documents` (topic) con colas: sign, send, authorize, notify
  - Exchange `key49.dlq` (fanout) con cola de dead letter
  - Record `DocumentEvent` como payload de mensajes
  - `DocumentEventProducer` con 4 canales de salida
  - 5 consumers skeleton: Sign, Send, Authorize, Notify, DLQ
  - 5 tests unitarios
- Autenticación por API Key con filtro reactivo (T-005)
  - `ApiKeyService`: generación de claves (fec*test*/fec*live*), SHA-256, validación
  - `ApiKeyAuthFilter`: @ServerRequestFilter con PgPool directo (JOIN api_keys+tenants)
  - Paths públicos excluidos: /q/, /portal/login, /openapi, /swagger-ui
  - Actualización asíncrona de `last_used_at` (fire-and-forget)
  - 15 tests unitarios + 5 tests de integración
- Validaciones de formato en frontera SRI (T-005a)
  - `SriValidator`: validación RUC (módulo 11), cédula (módulo 10)
  - Validadores de establishment (3 díg), issue_point (3 díg), sequence_number (9 díg)
  - Validación de identificación por tipo SRI (RUC, cédula, pasaporte, consumidor final)
  - Bean Validation annotations: `@ValidRuc`, `@ValidCedula`, `@ValidSriCode`
  - `ValidationExceptionMapper` con formato estándar de errores y detalles por campo
  - 67 tests unitarios (SriValidatorTest + BeanValidationAnnotationTest)

## [0.2.0] - 2026-04-05

### Agregado

- Schema-per-tenant con aislamiento por esquema PostgreSQL (T-002)
  - `TenantSchemaResolver`: resolución dinámica de esquema por UUID
  - `TenantContext`: CDI bean request-scoped para tenant activo
  - `TenantConnectionManager`: gestión de `SET search_path` por request
  - Scripts SQL de migración para esquemas tenant
  - 20 tests unitarios + 9 tests de integración
- Modelo de dominio y catálogos SRI (T-003)
  - 7 enums SRI: DocumentType, DocumentStatus, TaxType, VatRate, PaymentMethod, IdentificationType, SriEnvironment
  - 3 entidades JPA: Tenant, ApiKey, Document (con machine state)
  - 3 repositorios Panache reactivos: TenantRepository, ApiKeyRepository, DocumentRepository
  - State machine con `canTransitionTo()` y `InvalidStateTransitionException`
  - 114 tests unitarios + 9 tests de integración

## [0.1.0] - 2026-04-05

### Agregado

- Inicialización del proyecto Quarkus multi-módulo con Maven (T-001)
- Estructura de 10 módulos: api, core, xml, signer, sri, queue, ride, notify, storage, admin
- Parent POM con Quarkus 3.34.2 BOM y dependency management
- Perfiles Maven: dev, test, prod
- Configuración base en `application.properties`
- Documentación técnica organizada en `docs/`
- README.md informativo del proyecto
- .gitignore configurado

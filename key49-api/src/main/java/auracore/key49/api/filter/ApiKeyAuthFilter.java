package auracore.key49.api.filter;

import auracore.key49.core.service.ApiKeyService;
import auracore.key49.core.tenant.TenantContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.time.OffsetDateTime;
import java.time.Instant;

/**
 * Filtro de autenticación por API Key. Usa PgPool directamente (sin Hibernate
 * session) para consultar api_keys y tenants.
 */
public class ApiKeyAuthFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    @Inject
    Logger log;

    @Inject
    PgPool pgPool;

    @Inject
    TenantContext tenantContext;

    @ServerRequestFilter(priority = 10)
    public Uni<Response> authenticate(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();

        if (isPublicPath(path)) {
            return Uni.createFrom().nullItem();
        }

        var authHeader = requestContext.getHeaderString(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug("Missing or invalid Authorization header");
            return unauthorizedResponse("Missing or invalid Authorization header");
        }

        var rawKey = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawKey.isEmpty()) {
            return unauthorizedResponse("Empty API key");
        }

        var prefix = ApiKeyService.extractPrefix(rawKey);
        if (prefix == null) {
            return unauthorizedResponse("Invalid API key format");
        }

        var keyHash = ApiKeyService.sha256(rawKey);

        return pgPool.preparedQuery("""
                        SELECT a.tenant_id, a.status AS key_status, a.expires_at,
                               t.schema_name, t.status AS tenant_status,
                               t.rate_limit_rpm
                        FROM api_keys a JOIN tenants t ON a.tenant_id = t.tenant_id
                        WHERE a.key_hash = $1""")
                .execute(Tuple.of(keyHash))
                .map(rows -> rows.iterator())
                .chain(it -> {
                    if (!it.hasNext()) {
                        return unauthorizedResponse("Invalid API key");
                    }
                    Row row = it.next();

                    var keyStatus = row.getString("key_status");
                    if (!"active".equals(keyStatus)) {
                        return Uni.createFrom().item(forbiddenResponse("API key is not active"));
                    }

                    var expiresAt = row.getOffsetDateTime("expires_at");
                    if (expiresAt != null && Instant.now().isAfter(expiresAt.toInstant())) {
                        return Uni.createFrom().item(forbiddenResponse("API key has expired"));
                    }

                    var tenantStatus = row.getString("tenant_status");
                    if (!"active".equals(tenantStatus)) {
                        return Uni.createFrom().item(forbiddenResponse("Tenant is not active"));
                    }

                    var tenantId = row.getUUID("tenant_id");
                    var schemaName = row.getString("schema_name");
                    var rateLimitRpm = row.getInteger("rate_limit_rpm");
                    tenantContext.setTenant(tenantId, schemaName);
                    tenantContext.setRateLimitRpm(rateLimitRpm);
                    tenantContext.setApiKeyPrefix(prefix);
                    log.debugf("Authenticated tenant=%s, schema=%s", tenantId, schemaName);

                    // Update last_used_at asynchronously (fire-and-forget)
                    pgPool.preparedQuery("UPDATE api_keys SET last_used_at = now() WHERE key_hash = $1")
                            .execute(Tuple.of(keyHash)).subscribe().with(v -> {
                    }, e -> log.warnf("Failed to update last_used_at: %s", e.getMessage()));

                    return Uni.createFrom().nullItem();
                });
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/q/")
                || path.startsWith("/portal/login")
                || path.equals("/openapi")
                || path.equals("/swagger-ui");
    }

    private Uni<Response> unauthorizedResponse(String message) {
        return Uni.createFrom().item(
                Response.status(Response.Status.UNAUTHORIZED)
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .entity(errorBody(message))
                        .build());
    }

    private Response forbiddenResponse(String message) {
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(errorBody(message))
                .build();
    }

    private String errorBody(String message) {
        return "{\"error\":{\"code\":\"AUTHENTICATION_ERROR\",\"message\":\"" + message + "\"}}";
    }
}

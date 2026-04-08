package auracore.key49.api.filter;

import auracore.key49.core.service.ApiKeyService;
import auracore.key49.core.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Filtro de autenticación por API Key. Usa DataSource (JDBC) para consultar
 * api_keys y tenants en el esquema public.
 */
public class ApiKeyAuthFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    @Inject
    Logger log;

    @Inject
    DataSource dataSource;

    @Inject
    TenantContext tenantContext;

    @ServerRequestFilter(priority = 10)
    public Response authenticate(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();

        if (isPublicPath(path)) {
            return null;
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

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT a.tenant_id, a.status AS key_status, a.expires_at,
                            t.schema_name, t.status AS tenant_status,
                            t.rate_limit_rpm
                     FROM api_keys a JOIN tenants t ON a.tenant_id = t.tenant_id
                     WHERE a.key_hash = ?""")) {

            stmt.setString(1, keyHash);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return unauthorizedResponse("Invalid API key");
                }

                var keyStatus = rs.getString("key_status");
                if (!"active".equals(keyStatus)) {
                    return forbiddenResponse("API key is not active");
                }

                var expiresAt = rs.getTimestamp("expires_at");
                if (expiresAt != null && Instant.now().isAfter(expiresAt.toInstant())) {
                    return forbiddenResponse("API key has expired");
                }

                var tenantStatus = rs.getString("tenant_status");
                if (!"active".equals(tenantStatus)) {
                    return forbiddenResponse("Tenant is not active");
                }

                var tenantId = rs.getObject("tenant_id", java.util.UUID.class);
                var schemaName = rs.getString("schema_name");
                var rateLimitRpm = rs.getInt("rate_limit_rpm");
                tenantContext.setTenant(tenantId, schemaName);
                tenantContext.setRateLimitRpm(rateLimitRpm);
                tenantContext.setApiKeyPrefix(prefix);
                log.debugf("Authenticated tenant=%s, schema=%s", tenantId, schemaName);
            }

            // Update last_used_at (fire-and-forget in a separate connection)
            updateLastUsedAt(keyHash);

        } catch (SQLException e) {
            log.errorf(e, "Database error during API key authentication");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(errorBody("Internal server error"))
                    .build();
        }

        return null;
    }

    private void updateLastUsedAt(String keyHash) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE api_keys SET last_used_at = now() WHERE key_hash = ?")) {
            stmt.setString(1, keyHash);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.warnf("Failed to update last_used_at: %s", e.getMessage());
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/q/")
                || path.startsWith("/portal")
                || path.equals("/openapi")
                || path.equals("/swagger-ui");
    }

    private Response unauthorizedResponse(String message) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(errorBody(message))
                .build();
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
